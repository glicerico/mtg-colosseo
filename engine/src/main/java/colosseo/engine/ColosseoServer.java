package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import mage.cards.ExpansionSet;
import mage.cards.Sets;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MTG Colosseo server: hosts XMage games and exposes them to humans (web UI) and agents (JSON over WebSocket).
 *
 * <pre>
 * java -jar colosseo-engine.jar --port 7070 --web ../web --decks ../decks --data .
 * </pre>
 * <p>
 * REST
 * <ul>
 *   <li>GET  /api/health</li>
 *   <li>GET  /api/decks, GET /api/decks/{id}</li>
 *   <li>GET  /api/sets (sets available for "sealed:SET" decks)</li>
 *   <li>GET  /api/cards?name=... (card lookup)</li>
 *   <li>GET  /api/games, POST /api/games, GET /api/games/{id}, POST /api/games/{id}/terminate</li>
 * </ul>
 * WebSocket
 * <ul>
 *   <li>/ws/game/{id}?seat=0[&amp;token=SEAT_TOKEN]  control a seat (receive decisions, send actions)</li>
 *   <li>/ws/game/{id}?spectate=1[&amp;reveal=1&amp;token=OWNER_TOKEN]  watch (reveal = see both hands)</li>
 * </ul>
 * Credentials (seat token, owner token, API key) are passed as {@code token} query parameter, an
 * {@code Authorization: Bearer} header or an {@code X-Colosseo-Token} header. See {@link AccessPolicy}.
 */
public final class ColosseoServer {

    private static final Logger LOG = Logger.getLogger(ColosseoServer.class);
    public static final String VERSION = "0.1.0";

    /**
     * Sets offered in the lobby for sealed play (every XMage set works via the API).
     */
    public static final List<String> FEATURED_SETS = List.of("FDN", "BLB");

    private final GameManager manager;
    private final AccessPolicy policy;
    private final Map<WsContext, Connection> connections = new ConcurrentHashMap<>();

    private ColosseoServer(GameManager manager, AccessPolicy policy) {
        this.manager = manager;
        this.policy = policy;
    }

    private static final String USAGE = String.join("\n",
            "usage: colosseo-engine [options]",
            "  --port N               HTTP port (default 7070)",
            "  --host ADDR            bind address (default 127.0.0.1; use 0.0.0.0 to accept remote clients)",
            "  --auth open|tokens     open: anyone who can connect may control seats, reveal hands and stop games",
            "                         tokens: seat tokens / owner tokens required (default unless bound to loopback)",
            "  --require-tokens       same as --auth tokens",
            "  --api-key KEY          require KEY to create games (also a master key); env COLOSSEO_API_KEY",
            "  --cors-origin ORIGIN   allow browser pages from ORIGIN (repeatable, comma separated, * = any)",
            "  --allowed-host NAME    extra Host name accepted by an open loopback server (repeatable)",
            "  --deck-paths on|off    allow decks given as server file paths (default: on in open mode only)",
            "  --max-games N          maximum running games (default 32, 0 = unlimited)",
            "  --abandon-timeout S    stop a game when a bridge seat stays disconnected S seconds (default 600, 0 = never)",
            "  --web DIR --decks DIR --data DIR --verbose",
            "note: XMage keeps its card database in ./db of the working directory");

    public static void main(String[] args) {
        int port = 7070;
        String host = "127.0.0.1";
        Path web = Path.of("web");
        Path decks = Path.of("decks");
        Path data = Path.of(".");
        boolean verbose = false;
        AccessPolicy.Mode mode = null;
        String apiKey = System.getenv("COLOSSEO_API_KEY");
        List<String> origins = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        Boolean deckPaths = null;
        int maxGames = 32;
        double abandonTimeout = 600;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port":
                        port = Integer.parseInt(args[++i]);
                        break;
                    case "--host":
                        host = args[++i];
                        break;
                    case "--web":
                        web = Path.of(args[++i]);
                        break;
                    case "--decks":
                        decks = Path.of(args[++i]);
                        break;
                    case "--data":
                        data = Path.of(args[++i]);
                        break;
                    case "--verbose":
                        verbose = true;
                        break;
                    case "--auth":
                        mode = AccessPolicy.Mode.parse(args[++i]);
                        break;
                    case "--require-tokens":
                        mode = AccessPolicy.Mode.TOKENS;
                        break;
                    case "--api-key":
                        apiKey = args[++i];
                        break;
                    case "--cors-origin":
                        for (String o : args[++i].split(",")) {
                            if (!o.isBlank()) {
                                origins.add(o.trim());
                            }
                        }
                        break;
                    case "--allowed-host":
                        hosts.add(args[++i].trim());
                        break;
                    case "--deck-paths":
                        deckPaths = parseOnOff(args[++i]);
                        break;
                    case "--max-games":
                        maxGames = Integer.parseInt(args[++i]);
                        break;
                    case "--abandon-timeout":
                        abandonTimeout = Double.parseDouble(args[++i]);
                        break;
                    case "--help":
                    case "-h":
                        System.out.println(USAGE);
                        return;
                    default:
                        throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (RuntimeException e) {
            System.err.println(e instanceof ArrayIndexOutOfBoundsException ? "missing value for " + args[args.length - 1] : e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
        }

        AccessPolicy policy = AccessPolicy.forBind(host, mode, apiKey, deckPaths, origins, hosts);
        XmageBootstrap.init(verbose);
        DeckLibrary deckLibrary = new DeckLibrary(decks);
        deckLibrary.setAllowPaths(policy.allowDeckPaths);
        GameManager manager = new GameManager(deckLibrary, data.toAbsolutePath(), Math.max(0, maxGames), Math.max(0, abandonTimeout));
        ColosseoServer server = new ColosseoServer(manager, policy);
        server.start(host, port, web);
        if (policy.mode == AccessPolicy.Mode.OPEN && !AccessPolicy.isLoopback(host)) {
            LOG.warn("open auth mode on a non-loopback address: anyone who can reach this server can control seats, "
                    + "see both hands and stop games");
        }
    }

    private static boolean parseOnOff(String v) {
        switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "on":
            case "true":
            case "yes":
                return true;
            case "off":
            case "false":
            case "no":
                return false;
            default:
                throw new IllegalArgumentException("expected on or off: " + v);
        }
    }

    private void start(String host, int port, Path web) {
        boolean hasWeb = Files.isDirectory(web);
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            if (hasWeb) {
                config.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = web.toAbsolutePath().toString();
                    sf.location = Location.EXTERNAL;
                });
            }
            // browsers may only call the API from this server's own pages, plus explicitly configured origins
            if (!policy.corsOrigins().isEmpty()) {
                config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                    if (policy.corsOrigins().contains("*")) {
                        rule.anyHost();
                    } else {
                        policy.corsOrigins().forEach(o -> rule.allowHost(o));
                    }
                }));
            }
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(Duration.ofHours(4));
                factory.setMaxTextMessageSize(4 * 1024 * 1024);
            });
        });

        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("request failed", e);
            error(ctx, 500, e.toString());
        });

        // DNS rebinding guard (open loopback servers) and cross-site request guard for state-changing calls
        app.before(ctx -> {
            if (!policy.hostAllowed(ctx.header("Host"))) {
                error(ctx, 403, "unexpected Host header (start the server with --allowed-host to accept it)");
                ctx.skipRemainingHandlers();
                return;
            }
            if (!"GET".equals(ctx.method().name()) && !"OPTIONS".equals(ctx.method().name()) && !"HEAD".equals(ctx.method().name())
                    && !policy.originAllowed(ctx.header("Origin"), ctx.header("Host"))) {
                error(ctx, 403, "cross-origin request refused (allow it with --cors-origin)");
                ctx.skipRemainingHandlers();
            }
        });

        app.get("/api/health", ctx -> {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("version", VERSION);
            o.addProperty("games", manager.list().size());
            o.addProperty("running", manager.running());
            o.addProperty("max_games", manager.maxRunning());
            o.addProperty("auth", policy.mode.label());
            o.addProperty("api_key_required", policy.apiKeyRequired());
            o.addProperty("deck_paths", policy.allowDeckPaths);
            json(ctx, o);
        });

        app.get("/api/decks", ctx -> {
            JsonArray arr = new JsonArray();
            boolean full = "1".equals(ctx.queryParam("full"));
            manager.decks().reload();
            for (DeckLibrary.DeckInfo info : manager.decks().list()) {
                if (full) {
                    arr.add(manager.decks().describe(info));
                } else {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", info.id);
                    o.addProperty("name", info.name);
                    o.addProperty("description", info.description);
                    o.add("tags", Json.strings(info.tags));
                    arr.add(o);
                }
            }
            json(ctx, arr);
        });

        app.get("/api/decks/{id}", ctx -> {
            DeckLibrary.DeckInfo info = manager.decks().get(ctx.pathParam("id"));
            if (info == null) {
                error(ctx, 404, "unknown deck");
                return;
            }
            json(ctx, manager.decks().describe(info));
        });

        app.get("/api/sets", ctx -> {
            JsonArray arr = new JsonArray();
            for (String code : FEATURED_SETS) {
                ExpansionSet set = Sets.findSet(code);
                if (set != null) {
                    JsonObject o = new JsonObject();
                    o.addProperty("code", set.getCode());
                    o.addProperty("name", set.getName());
                    o.addProperty("deck", "sealed:" + set.getCode());
                    arr.add(o);
                }
            }
            json(ctx, arr);
        });

        app.get("/api/sets/{code}/cards", ctx -> {
            String code = ctx.pathParam("code").toUpperCase();
            JsonArray arr = new JsonArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (CardInfo ci : CardRepository.instance.findCards(new mage.cards.repository.CardCriteria().setCodes(code))) {
                if (!seen.add(ci.getName())) {
                    continue; // skip alternate printings
                }
                arr.add(cardInfoJson(ci));
            }
            json(ctx, arr);
        });

        app.get("/api/cards", ctx -> {
            String name = ctx.queryParam("name");
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name query parameter required");
            }
            JsonArray arr = new JsonArray();
            for (CardInfo ci : CardRepository.instance.findCards(name)) {
                arr.add(cardInfoJson(ci));
                if (arr.size() >= 20) {
                    break;
                }
            }
            json(ctx, arr);
        });

        app.get("/api/games", ctx -> {
            JsonArray arr = new JsonArray();
            for (com.google.gson.JsonElement el : manager.summaries()) {
                JsonObject g = el.getAsJsonObject();
                GameSession session = manager.get(Json.getString(g, "id", ""));
                g.addProperty("protected", session != null && policy.isProtected(session.config));
                arr.add(g);
            }
            json(ctx, arr);
        });

        app.post("/api/games", ctx -> {
            if (!policy.canCreate(credential(ctx))) {
                error(ctx, 401, "an API key is required to create games");
                return;
            }
            String contentType = ctx.contentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                error(ctx, 415, "expected Content-Type: application/json");
                return;
            }
            GameConfig config = GameConfig.parse(Json.parse(ctx.body()));
            GameSession session;
            try {
                session = manager.create(config);
            } catch (GameManager.TooManyGamesException e) {
                error(ctx, 429, e.getMessage());
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("game_id", session.id);
            o.add("game", gameJson(session));
            // secrets: only ever returned here, to the creator
            o.addProperty("owner_token", session.ownerToken);
            JsonArray seats = new JsonArray();
            for (Seat seat : session.seats) {
                JsonObject s = new JsonObject();
                s.addProperty("seat", seat.index);
                s.addProperty("name", seat.config().name);
                s.addProperty("type", seat.config().type);
                if (seat.config().isBridge()) {
                    s.addProperty("token", seat.token);
                    s.addProperty("ws", "/ws/game/" + session.id + "?seat=" + seat.index + "&token=" + seat.token);
                    s.addProperty("url", "/#/game/" + session.id + "/seat/" + seat.index + "?token=" + seat.token);
                }
                seats.add(s);
            }
            o.add("seats", seats);
            o.addProperty("spectate_ws", "/ws/game/" + session.id + "?spectate=1");
            o.addProperty("reveal_ws", "/ws/game/" + session.id + "?spectate=1&reveal=1&token=" + session.ownerToken);
            o.addProperty("watch_url", "/#/watch/" + session.id);
            o.addProperty("owner_url", "/#/watch/" + session.id + "?token=" + session.ownerToken);
            ctx.status(201);
            json(ctx, o);
        });

        app.get("/api/games/{id}", ctx -> {
            GameSession session = manager.get(ctx.pathParam("id"));
            if (session == null) {
                error(ctx, 404, "unknown game");
                return;
            }
            json(ctx, gameJson(session));
        });

        app.post("/api/games/{id}/terminate", ctx -> {
            GameSession session = manager.get(ctx.pathParam("id"));
            if (session == null) {
                error(ctx, 404, "unknown game");
                return;
            }
            if (!policy.canAdminister(session.config, session.ownerToken, credential(ctx))) {
                error(ctx, 403, "stopping this game requires its owner token");
                return;
            }
            session.terminate();
            session.awaitEnd(3000);
            json(ctx, gameJson(session));
        });

        app.ws("/ws/game/{id}", ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings();
                Connection c = new Connection(ctx);
                connections.put(ctx, c);
                if (!policy.hostAllowed(ctx.header("Host")) || !policy.originAllowed(ctx.header("Origin"), ctx.header("Host"))) {
                    c.reject("connection from this origin is not allowed", 4003, "forbidden origin");
                    return;
                }
                GameSession session = manager.get(ctx.pathParam("id"));
                if (session == null) {
                    c.reject("unknown game " + ctx.pathParam("id"), 4004, "unknown game");
                    return;
                }
                String token = ctx.queryParam("token");
                String seatParam = ctx.queryParam("seat");
                if (seatParam != null) {
                    Seat seat;
                    try {
                        seat = session.seat(Integer.parseInt(seatParam));
                    } catch (NumberFormatException e) {
                        seat = null;
                    }
                    if (seat == null || !seat.config().isBridge()) {
                        c.reject("seat " + seatParam + " can't be controlled remotely", 4003, "bad seat");
                        return;
                    }
                    if (!policy.canControlSeat(session.config, seat.token, token)) {
                        c.reject((token == null || token.isEmpty() ? "a token is required" : "invalid token")
                                + " for seat " + seatParam, 4001, "bad token");
                        return;
                    }
                    session.attachPlayer(seat, c);
                } else {
                    c.mayReveal = policy.canAdminister(session.config, session.ownerToken, token);
                    boolean wantsReveal = "1".equals(ctx.queryParam("reveal")) || "true".equals(ctx.queryParam("reveal"));
                    c.revealAll = wantsReveal && c.mayReveal;
                    session.attachSpectator(c);
                    if (wantsReveal && !c.mayReveal) {
                        c.sendError("revealing hands requires the game's owner token; watching without hidden information", null);
                    }
                }
            });
            ws.onMessage(ctx -> {
                Connection c = connections.get(ctx);
                GameSession session = manager.get(ctx.pathParam("id"));
                if (c == null || session == null) {
                    return;
                }
                handleMessage(session, c, ctx.message());
            });
            ws.onClose(ctx -> {
                Connection c = connections.remove(ctx);
                GameSession session = manager.get(ctx.pathParam("id"));
                if (c != null && session != null) {
                    session.detach(c);
                }
            });
            ws.onError(ctx -> {
                Connection c = connections.remove(ctx);
                GameSession session = manager.get(ctx.pathParam("id"));
                if (c != null && session != null) {
                    session.detach(c);
                }
            });
        });

        app.start(host, port);
        LOG.info("MTG Colosseo " + VERSION + " listening on http://" + ("0.0.0.0".equals(host) ? "localhost" : host) + ":" + port
                + " (auth: " + policy.mode.label() + (policy.apiKeyRequired() ? ", API key required to create games" : "") + ")"
                + (hasWeb ? "" : " (no web UI directory found at " + web.toAbsolutePath() + ")"));
    }

    private void handleMessage(GameSession session, Connection c, String text) {
        JsonObject msg;
        try {
            msg = Json.parse(text);
        } catch (RuntimeException e) {
            c.sendError("invalid JSON: " + e.getMessage(), null);
            return;
        }
        String type = Json.getString(msg, "type", "action");
        Seat seat = c.seat;
        switch (type) {
            case "ping": {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                c.send(pong);
                break;
            }
            case "action":
                if (seat == null) {
                    c.sendError("spectators can't act", null);
                    return;
                }
                session.submit(seat, msg, c);
                break;
            case "concede":
                if (seat == null) {
                    c.sendError("spectators can't concede", null);
                    return;
                }
                session.concede(seat);
                break;
            case "settings":
                if (seat == null) {
                    if (msg.has("token")) {
                        c.mayReveal = c.mayReveal || policy.canAdminister(session.config, session.ownerToken, Json.getString(msg, "token", null));
                    }
                    if (msg.has("reveal")) {
                        boolean reveal = Json.getBool(msg, "reveal", false);
                        if (reveal && !c.mayReveal) {
                            c.sendError("revealing hands requires the game's owner token", null);
                            return;
                        }
                        c.revealAll = reveal;
                        session.spectatorChanged(c);
                    }
                    return;
                }
                if (msg.has("stop_policy")) {
                    seat.setStopPolicy(Json.getString(msg, "stop_policy", "arena"));
                }
                if (msg.has("yield_after_cast")) {
                    seat.setYieldAfterCast(Json.getBool(msg, "yield_after_cast", true));
                }
                break;
            default:
                c.sendError("unknown message type: " + type, null);
        }
    }

    private JsonObject gameJson(GameSession session) {
        JsonObject o = session.summary();
        o.addProperty("protected", policy.isProtected(session.config));
        return o;
    }

    /**
     * Seat token, owner token or API key presented by an HTTP request.
     */
    private static String credential(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String header = ctx.header("X-Colosseo-Token");
        if (header != null && !header.isEmpty()) {
            return header.trim();
        }
        return ctx.queryParam("token");
    }

    private static JsonObject cardInfoJson(CardInfo ci) {
        JsonObject o = new JsonObject();
        o.addProperty("name", ci.getName());
        o.addProperty("set", ci.getSetCode());
        o.addProperty("number", ci.getCardNumber());
        o.addProperty("rarity", ci.getRarity() == null ? null : ci.getRarity().toString());
        o.addProperty("mana_cost", String.join("", ci.getManaCosts(CardInfo.ManaCostSide.ALL)));
        o.addProperty("mana_value", ci.getManaValue());
        o.addProperty("colors", ci.getColor().toString());
        o.add("types", Json.strings(ci.getTypes().stream().map(Object::toString).collect(java.util.stream.Collectors.toList())));
        o.add("subtypes", Json.strings(ci.getSubTypes().stream().map(Object::toString).collect(java.util.stream.Collectors.toList())));
        o.addProperty("power", ci.getPower());
        o.addProperty("toughness", ci.getToughness());
        JsonArray rules = new JsonArray();
        for (String r : ci.getRules()) {
            String t = Json.plain(r).replace("{this}", ci.getName());
            if (!t.isEmpty()) {
                rules.add(t);
            }
        }
        o.add("rules", rules);
        return o;
    }

    private static void json(Context ctx, com.google.gson.JsonElement body) {
        ctx.contentType("application/json");
        ctx.result(Json.GSON.toJson(body));
    }

    private static void error(Context ctx, int status, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        ctx.status(status);
        json(ctx, o);
    }
}
