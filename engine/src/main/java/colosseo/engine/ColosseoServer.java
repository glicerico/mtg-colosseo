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
 *   <li>/ws/game/{id}?seat=0[&amp;token=...]  control a seat (receive decisions, send actions)</li>
 *   <li>/ws/game/{id}?spectate=1[&amp;reveal=1]  watch (reveal = see both hands)</li>
 * </ul>
 */
public final class ColosseoServer {

    private static final Logger LOG = Logger.getLogger(ColosseoServer.class);
    public static final String VERSION = "0.1.0";

    /**
     * Sets offered in the lobby for sealed play (every XMage set works via the API).
     */
    public static final List<String> FEATURED_SETS = List.of("FDN", "DSK");

    private final GameManager manager;
    private final boolean requireTokens;
    private final Map<WsContext, Connection> connections = new ConcurrentHashMap<>();

    private ColosseoServer(GameManager manager, boolean requireTokens) {
        this.manager = manager;
        this.requireTokens = requireTokens;
    }

    public static void main(String[] args) {
        int port = 7070;
        String host = "0.0.0.0";
        Path web = Path.of("web");
        Path decks = Path.of("decks");
        Path data = Path.of(".");
        boolean verbose = false;
        boolean requireTokens = false;
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
                case "--require-tokens":
                    requireTokens = true;
                    break;
                case "--help":
                case "-h":
                    System.out.println("usage: colosseo-engine [--port 7070] [--host 0.0.0.0] [--web DIR] [--decks DIR] [--data DIR] [--require-tokens] [--verbose]");
                    System.out.println("note: XMage keeps its card database in ./db of the working directory");
                    return;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        XmageBootstrap.init(verbose);
        DeckLibrary deckLibrary = new DeckLibrary(decks);
        GameManager manager = new GameManager(deckLibrary, data.toAbsolutePath());
        ColosseoServer server = new ColosseoServer(manager, requireTokens);
        server.start(host, port, web);
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
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
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

        app.get("/api/health", ctx -> {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("version", VERSION);
            o.addProperty("games", manager.list().size());
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

        app.get("/api/games", ctx -> json(ctx, manager.summaries()));

        app.post("/api/games", ctx -> {
            GameConfig config = GameConfig.parse(Json.parse(ctx.body()));
            GameSession session = manager.create(config);
            JsonObject o = new JsonObject();
            o.addProperty("game_id", session.id);
            o.add("game", session.summary());
            JsonArray seats = new JsonArray();
            for (Seat seat : session.seats) {
                JsonObject s = new JsonObject();
                s.addProperty("seat", seat.index);
                s.addProperty("name", seat.config().name);
                s.addProperty("type", seat.config().type);
                if (seat.config().isBridge()) {
                    s.addProperty("token", seat.token);
                    s.addProperty("ws", "/ws/game/" + session.id + "?seat=" + seat.index + "&token=" + seat.token);
                }
                seats.add(s);
            }
            o.add("seats", seats);
            o.addProperty("spectate_ws", "/ws/game/" + session.id + "?spectate=1");
            ctx.status(201);
            json(ctx, o);
        });

        app.get("/api/games/{id}", ctx -> {
            GameSession session = manager.get(ctx.pathParam("id"));
            if (session == null) {
                error(ctx, 404, "unknown game");
                return;
            }
            json(ctx, session.summary());
        });

        app.post("/api/games/{id}/terminate", ctx -> {
            GameSession session = manager.get(ctx.pathParam("id"));
            if (session == null) {
                error(ctx, 404, "unknown game");
                return;
            }
            session.terminate();
            json(ctx, session.summary());
        });

        app.ws("/ws/game/{id}", ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings();
                Connection c = new Connection(ctx);
                connections.put(ctx, c);
                GameSession session = manager.get(ctx.pathParam("id"));
                if (session == null) {
                    c.sendError("unknown game " + ctx.pathParam("id"), null);
                    ctx.closeSession(4004, "unknown game");
                    return;
                }
                String seatParam = ctx.queryParam("seat");
                if (seatParam != null) {
                    Seat seat;
                    try {
                        seat = session.seat(Integer.parseInt(seatParam));
                    } catch (NumberFormatException e) {
                        seat = null;
                    }
                    if (seat == null || !seat.config().isBridge()) {
                        c.sendError("seat " + seatParam + " can't be controlled remotely", null);
                        ctx.closeSession(4003, "bad seat");
                        return;
                    }
                    String token = ctx.queryParam("token");
                    if (requireTokens && !seat.token.equals(token)) {
                        c.sendError("bad token for seat " + seatParam, null);
                        ctx.closeSession(4001, "bad token");
                        return;
                    }
                    session.attachPlayer(seat, c);
                } else {
                    c.revealAll = "1".equals(ctx.queryParam("reveal"));
                    session.attachSpectator(c);
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
                    if (msg.has("reveal")) {
                        c.revealAll = Json.getBool(msg, "reveal", false);
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
            String t = Json.plain(r);
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
