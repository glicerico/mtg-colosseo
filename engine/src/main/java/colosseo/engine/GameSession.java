package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.match.MatchOptions;
import mage.game.events.Listener;
import mage.game.events.PlayerQueryEvent;
import mage.game.events.TableEvent;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ComputerPlayerControllableProxy;
import mage.players.Player;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.players.net.UserSkipPrioritySteps;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One running game: owns the XMage game, its thread, the seats and the attached clients.
 * <p>
 * Threading: XMage runs the whole game on one "GAME ..." thread. Player dialogs block that thread until
 * an answer arrives from a client (WebSocket threads). Answers are validated on the client thread and
 * applied on a per-game responder thread.
 */
public final class GameSession {

    private static final Logger LOG = Logger.getLogger(GameSession.class);

    public final String id;
    public final GameConfig config;
    private final GameManager manager;
    final List<Seat> seats = new ArrayList<>();
    private final Map<UUID, Seat> seatByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> seatIndex = new HashMap<>();
    private final List<JsonObject> log = Collections.synchronizedList(new ArrayList<>());
    private final Set<Connection> spectators = ConcurrentHashMap.newKeySet();
    private final ExecutorService responder;
    private final AtomicLong decisionIds = new AtomicLong();
    private final AtomicLong decisionCount = new AtomicLong();

    private volatile String status = "created";
    private volatile JsonObject result;
    private volatile String error;
    private Game game;
    private Thread thread;
    private final long createdAt = System.currentTimeMillis();
    private volatile long startedAt;
    private volatile long endedAt;
    private boolean drawDeclared;
    private BufferedWriter recorder;

    // cached views for newly attached clients (written by the game thread)
    private final Map<Integer, JsonObject> lastSeatState = new ConcurrentHashMap<>();
    private volatile JsonObject lastSpectatorState;
    private volatile JsonObject lastSpectatorStateRevealed;

    GameSession(String id, GameConfig config, GameManager manager) {
        this.id = id;
        this.config = config;
        this.manager = manager;
        this.responder = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "colosseo-responder-" + id);
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < config.seats.size(); i++) {
            seats.add(new Seat(i, config.seats.get(i), this, UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
        }
    }

    ScheduledExecutorService timers() {
        return manager.timers();
    }

    public String status() {
        return status;
    }

    public Seat seat(int index) {
        return index >= 0 && index < seats.size() ? seats.get(index) : null;
    }

    // ---------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates players, loads decks and starts the game thread. Deck errors are reported synchronously.
     */
    void start() {
        if (config.seed != null) {
            RandomUtil.setSeed(config.seed);
        }
        game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 40, config.startingLife, 7);
        GameOptions options = new GameOptions();
        options.rollbackTurnsAllowed = false;
        game.setGameOptions(options);
        // AI simulations copy MatchPlayer data, so every player must belong to a match
        TwoPlayerMatch match = new TwoPlayerMatch(new MatchOptions("colosseo " + id, "Two Player Duel", false));

        for (Seat seat : seats) {
            GameConfig.SeatConfig sc = seat.config();
            Deck deck = manager.decks().load(sc.deck);
            Player player = createPlayer(sc);
            seat.player = player;
            seat.playerId = player.getId();
            if (player instanceof BridgePlayer) {
                ((BridgePlayer) player).attachSeat(seat);
            }
            game.loadCards(deck.getCards(), player.getId());
            game.addPlayer(player, deck);
            match.addPlayer(player, deck);
            seatByPlayer.put(player.getId(), seat);
            seatIndex.put(player.getId(), seat.index);
        }

        game.addTableEventListener((Listener<TableEvent>) this::onTableEvent);
        game.addPlayerQueryEventListener((Listener<PlayerQueryEvent>) this::onQuery);

        int starting = config.startingSeat >= 0 ? config.startingSeat : RandomUtil.nextInt(seats.size());
        game.setStartingPlayerId(seats.get(starting).playerId);

        openRecorder();
        record("config", configJson());

        thread = new Thread(this::run, "GAME colosseo " + id);
        thread.setDaemon(true);
        status = "running";
        startedAt = System.currentTimeMillis();
        thread.start();
    }

    private Player createPlayer(GameConfig.SeatConfig sc) {
        Player player;
        switch (sc.type) {
            case "xmage":
                player = new ComputerPlayerControllableProxy(sc.name, RangeOfInfluence.ONE, sc.skill);
                player.setUserData(UserData.getDefaultUserDataView());
                break;
            default:
                player = new BridgePlayer(sc.name);
                // mana in the pool is always used automatically (no "restricted" stock mode)
                player.setUserData(new UserData(UserGroup.DEFAULT, 0, false, false, new UserSkipPrioritySteps(),
                        "world", false, true, false, false, false, true, 1, true, false, ""));
        }
        return player;
    }

    private void run() {
        try {
            game.start(game.getStartingPlayerId());
        } catch (Throwable t) {
            LOG.error("game " + id + " crashed", t);
            error = t.toString();
        } finally {
            finish();
        }
    }

    private void finish() {
        endedAt = System.currentTimeMillis();
        for (Seat seat : seats) {
            seat.pending.set(null);
            seat.cancelTimeout();
        }
        JsonObject r = new JsonObject();
        Integer winner = null;
        for (Seat seat : seats) {
            if (seat.player != null && seat.player.hasWon()) {
                winner = seat.index;
            }
        }
        r.addProperty("winner_seat", winner);
        r.addProperty("winner", winner == null ? null : seats.get(winner).config().name);
        r.addProperty("draw", winner == null && error == null);
        r.addProperty("turns", game == null ? 0 : game.getTurnNum());
        r.addProperty("decisions", decisionCount.get());
        r.addProperty("duration_s", (endedAt - startedAt) / 1000.0);
        if (error != null) {
            r.addProperty("error", error);
        }
        JsonArray players = new JsonArray();
        for (Seat seat : seats) {
            JsonObject p = new JsonObject();
            p.addProperty("seat", seat.index);
            p.addProperty("name", seat.config().name);
            p.addProperty("type", seat.config().type);
            p.addProperty("deck", seat.config().deck);
            if (seat.player != null) {
                p.addProperty("life", seat.player.getLife());
                p.addProperty("won", seat.player.hasWon());
                p.addProperty("lost", seat.player.hasLost());
            }
            players.add(p);
        }
        r.add("players", players);
        result = r;
        status = error != null ? "error" : "finished";

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "game_over");
        msg.addProperty("game_id", id);
        msg.add("result", r);
        record("result", r);
        closeRecorder();

        for (Seat seat : seats) {
            JsonObject m = msg.deepCopy();
            try {
                if (game != null && seat.hasConnections()) {
                    m.add("state", StateView.build(game, seat.playerId, true, seatIndex));
                }
            } catch (RuntimeException e) {
                LOG.debug("final state failed", e);
            }
            seat.broadcast(m);
        }
        JsonObject specMsg = msg.deepCopy();
        try {
            if (game != null && !spectators.isEmpty()) {
                specMsg.add("state", StateView.build(game, null, true, seatIndex));
            }
        } catch (RuntimeException e) {
            LOG.debug("final state failed", e);
        }
        broadcastSpectators(specMsg);
        responder.shutdown();
        manager.onFinished(this);
    }

    /**
     * Stops a running game (both players leave).
     */
    public void terminate() {
        if (game == null || !"running".equals(status)) {
            return;
        }
        error = "terminated";
        for (Seat seat : seats) {
            if (seat.player != null) {
                seat.player.abort();
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void concede(Seat seat) {
        if (game == null || !"running".equals(status) || seat.player == null) {
            return;
        }
        addLog(seat.config().name + " concedes");
        game.setConcedingPlayer(seat.playerId);
    }

    // ---------------------------------------------------------------------------------------------
    // engine events (game thread)
    // ---------------------------------------------------------------------------------------------

    private void onTableEvent(TableEvent event) {
        switch (event.getEventType()) {
            case INFO:
            case STATUS:
                addLog(Json.plain(event.getMessage()));
                break;
            case ERROR:
                addLog("ERROR: " + Json.plain(event.getMessage()));
                break;
            case UPDATE:
                onUpdate();
                break;
            default:
                break;
        }
    }

    private void onUpdate() {
        if (config.maxTurns > 0 && game.getTurnNum() > config.maxTurns && !drawDeclared) {
            drawDeclared = true;
            addLog("Turn limit (" + config.maxTurns + ") reached: the game is a draw");
            game.setDraw(seats.get(0).playerId);
            return;
        }
        boolean watched = false;
        for (Seat seat : seats) {
            if (seat.hasConnections()) {
                watched = true;
                JsonObject state = StateView.build(game, seat.playerId, false, seatIndex);
                lastSeatState.put(seat.index, state);
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "state");
                msg.add("state", state);
                seat.broadcast(msg);
            }
        }
        if (!spectators.isEmpty()) {
            watched = true;
            sendSpectatorStates();
        }
        if (watched && config.paceMs > 0) {
            try {
                Thread.sleep(config.paceMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendSpectatorStates() {
        boolean needNormal = false;
        boolean needRevealed = false;
        for (Connection c : spectators) {
            if (c.revealAll) {
                needRevealed = true;
            } else {
                needNormal = true;
            }
        }
        if (needNormal) {
            lastSpectatorState = StateView.build(game, null, false, seatIndex);
        }
        if (needRevealed) {
            lastSpectatorStateRevealed = StateView.build(game, null, true, seatIndex);
        }
        for (Connection c : spectators) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "state");
            msg.add("state", c.revealAll ? lastSpectatorStateRevealed : lastSpectatorState);
            c.send(msg);
        }
    }

    private void onQuery(PlayerQueryEvent event) {
        Seat seat = seatByPlayer.get(event.getPlayerId());
        if (seat == null || !(seat.player instanceof BridgePlayer)) {
            return; // AI players raise events too (e.g. priority), nothing to do
        }
        if (event.getQueryType() == PlayerQueryEvent.QueryType.PERSONAL_MESSAGE) {
            String text = Json.plain(event.getMessage());
            seat.setInfo(text);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "info");
            msg.addProperty("message", text);
            seat.broadcast(msg);
            return;
        }
        Decision d = QueryTranslator.translate(this, seat, (BridgePlayer) seat.player, game, event);
        if (d != null) {
            publish(seat, d);
        }
    }

    private void addLog(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("i", log.size());
        entry.addProperty("turn", game == null ? 0 : game.getTurnNum());
        entry.addProperty("text", text);
        log.add(entry);
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "log");
        msg.add("entry", entry);
        for (Seat seat : seats) {
            seat.broadcast(msg);
        }
        broadcastSpectators(msg);
    }

    // ---------------------------------------------------------------------------------------------
    // decisions
    // ---------------------------------------------------------------------------------------------

    Decision newDecision(String kind, UUID playerId) {
        return new Decision(decisionIds.incrementAndGet(), kind, playerId);
    }

    /**
     * Publishes a decision to the seat's clients (game thread; the caller then waits for the answer).
     */
    void publish(Seat seat, Decision d) {
        JsonObject queued = seat.nextQueuedChoice(d);
        if (queued != null) {
            d.responder.validate(queued);
            responder.execute(() -> d.responder.apply(queued));
            return;
        }
        decisionCount.incrementAndGet();
        d.state = StateView.build(game, seat.playerId, false, seatIndex);
        d.newLog = seat.takeNewLog(log);
        lastSeatState.put(seat.index, d.state);
        seat.pending.set(d);

        JsonObject msg = d.toJson(id, seat.index);
        record("decision", msg);
        seat.broadcast(msg);
        seat.scheduleTimeout(d);

        if (!spectators.isEmpty()) {
            JsonObject note = new JsonObject();
            note.addProperty("type", "decision_pending");
            note.addProperty("seat", seat.index);
            note.addProperty("decision_id", d.id);
            note.addProperty("kind", d.kind);
            note.addProperty("prompt", d.prompt);
            broadcastSpectators(note);
            sendSpectatorStates();
        }
    }

    /**
     * Receives an answer (any thread). Returns null on success, otherwise an error message.
     */
    String submit(Seat seat, JsonObject action, Connection from) {
        Decision d = seat.pending.get();
        long did = Json.getLong(action, "decision_id", -1);
        String err = null;
        if (d == null) {
            err = "no decision pending for seat " + seat.index;
        } else if (did != d.id) {
            err = "stale decision_id " + did + " (pending: " + d.id + ")";
        } else {
            try {
                err = d.responder.validate(action);
            } catch (RuntimeException e) {
                err = "invalid action: " + e.getMessage();
            }
        }
        if (err == null && !seat.pending.compareAndSet(d, null)) {
            err = "decision " + did + " was already answered";
        }
        if (err != null) {
            if (from != null) {
                from.sendError(err, did >= 0 ? did : null);
            }
            return err;
        }
        seat.cancelTimeout();
        record("action", actionRecord(seat, d, action));
        responder.execute(() -> {
            try {
                d.responder.apply(action);
            } catch (RuntimeException e) {
                LOG.error("applying action failed", e);
            }
        });

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "action");
        ack.addProperty("seat", seat.index);
        ack.addProperty("decision_id", d.id);
        ack.addProperty("kind", d.kind);
        ack.addProperty("summary", summarize(d, action));
        String comment = Json.getString(action, "comment", null);
        if (comment != null) {
            ack.addProperty("comment", comment);
        }
        seat.broadcast(ack);
        broadcastSpectators(ack);
        for (Seat other : seats) {
            if (other != seat) {
                other.broadcast(ack);
            }
        }
        return null;
    }

    private JsonObject actionRecord(Seat seat, Decision d, JsonObject action) {
        JsonObject r = new JsonObject();
        r.addProperty("seat", seat.index);
        r.addProperty("decision_id", d.id);
        r.addProperty("kind", d.kind);
        r.add("action", action);
        return r;
    }

    private static String summarize(Decision d, JsonObject action) {
        StringBuilder sb = new StringBuilder();
        if (action.has("choice")) {
            String c = Json.getString(action, "choice", "");
            JsonObject opt = d.options.get(c);
            sb.append(opt == null ? c : Json.getString(opt, "label", c));
        }
        if (action.has("choices") && action.get("choices").isJsonArray()) {
            List<String> labels = new ArrayList<>();
            for (JsonElement el : action.getAsJsonArray("choices")) {
                JsonObject opt = d.options.get(el.getAsString());
                labels.add(opt == null ? el.getAsString() : Json.getString(opt, "label", el.getAsString()));
            }
            sb.append(String.join(", ", labels));
        }
        if (action.has("amount")) {
            sb.append(action.get("amount").getAsInt());
        }
        if (action.has("amounts")) {
            sb.append(action.get("amounts").toString());
        }
        if (action.has("attackers") && action.get("attackers").isJsonArray()) {
            JsonArray arr = action.getAsJsonArray("attackers");
            if (arr.size() == 0) {
                sb.append("no attack");
            } else {
                List<String> labels = new ArrayList<>();
                for (JsonElement el : arr) {
                    String a = Json.getString(el.getAsJsonObject(), "attacker", "");
                    JsonObject opt = d.options.get(a);
                    labels.add(opt == null ? a : Json.getString(opt, "label", a));
                }
                sb.append("attack with ").append(String.join(", ", labels));
            }
        }
        if (action.has("blocks") && action.get("blocks").isJsonArray()) {
            JsonArray arr = action.getAsJsonArray("blocks");
            sb.append(arr.size() == 0 ? "no blocks" : arr.size() + " block(s)");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // clients
    // ---------------------------------------------------------------------------------------------

    void attachPlayer(Seat seat, Connection c) {
        c.seat = seat;
        seat.connections.add(c);
        JsonObject hello = hello(seat.index);
        hello.addProperty("role", "player");
        JsonObject state = lastSeatState.get(seat.index);
        if (state == null) {
            state = tryBuildState(seat.playerId, false);
        }
        if (state != null) {
            hello.add("state", state);
        }
        hello.add("log", logTail(200));
        c.send(hello);
        Decision d = seat.pending.get();
        if (d != null) {
            c.send(d.toJson(id, seat.index));
        }
        if (result != null) {
            JsonObject over = new JsonObject();
            over.addProperty("type", "game_over");
            over.addProperty("game_id", id);
            over.add("result", result);
            c.send(over);
        }
    }

    void attachSpectator(Connection c) {
        spectators.add(c);
        JsonObject hello = hello(-1);
        hello.addProperty("role", "spectator");
        JsonObject state = c.revealAll ? lastSpectatorStateRevealed : lastSpectatorState;
        if (state == null) {
            state = tryBuildState(null, c.revealAll);
        }
        if (state != null) {
            hello.add("state", state);
        }
        hello.add("log", logTail(200));
        c.send(hello);
        if (result != null) {
            JsonObject over = new JsonObject();
            over.addProperty("type", "game_over");
            over.addProperty("game_id", id);
            over.add("result", result);
            c.send(over);
        }
    }

    void detach(Connection c) {
        spectators.remove(c);
        for (Seat seat : seats) {
            seat.connections.remove(c);
        }
    }

    /**
     * Builds a state outside the game thread; only safe-ish while the game waits for a decision.
     */
    private JsonObject tryBuildState(UUID viewer, boolean revealAll) {
        if (game == null) {
            return null;
        }
        boolean waiting = seats.stream().anyMatch(s -> s.pending.get() != null) || !"running".equals(status);
        if (!waiting) {
            return null;
        }
        try {
            return StateView.build(game, viewer, revealAll, seatIndex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonArray logTail(int n) {
        JsonArray arr = new JsonArray();
        synchronized (log) {
            for (int i = Math.max(0, log.size() - n); i < log.size(); i++) {
                arr.add(log.get(i));
            }
        }
        return arr;
    }

    private JsonObject hello(int seatIndex) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("game_id", id);
        o.addProperty("seat", seatIndex);
        o.add("game", summary());
        return o;
    }

    private void broadcastSpectators(JsonObject msg) {
        if (spectators.isEmpty()) {
            return;
        }
        String text = Json.GSON.toJson(msg);
        for (Connection c : spectators) {
            c.send(text);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // lobby info
    // ---------------------------------------------------------------------------------------------

    public JsonObject summary() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", config.title);
        o.addProperty("status", status);
        o.addProperty("created_at", createdAt);
        o.addProperty("turn", game == null ? 0 : game.getTurnNum());
        JsonArray arr = new JsonArray();
        for (Seat seat : seats) {
            JsonObject s = seat.config().toJson();
            s.addProperty("seat", seat.index);
            s.addProperty("connected", seat.hasConnections());
            s.addProperty("player_id", Json.str(seat.playerId));
            s.addProperty("waiting_for_decision", seat.pending.get() != null);
            arr.add(s);
        }
        o.add("seats", arr);
        o.addProperty("spectators", spectators.size());
        if (result != null) {
            o.add("result", result);
        }
        return o;
    }

    private JsonObject configJson() {
        JsonObject o = config.toJson();
        o.addProperty("game_id", id);
        JsonArray players = new JsonArray();
        for (Seat seat : seats) {
            JsonObject p = new JsonObject();
            p.addProperty("seat", seat.index);
            p.addProperty("player_id", Json.str(seat.playerId));
            players.add(p);
        }
        o.add("players", players);
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // recording (JSONL: config, decision, action, result)
    // ---------------------------------------------------------------------------------------------

    private void openRecorder() {
        if (!config.record) {
            return;
        }
        try {
            Path dir = manager.dataDir().resolve("games");
            Files.createDirectories(dir);
            recorder = Files.newBufferedWriter(dir.resolve(id + ".jsonl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("can't open game record: " + e.getMessage());
        }
    }

    private synchronized void record(String type, JsonObject payload) {
        if (recorder == null) {
            return;
        }
        try {
            JsonObject line = new JsonObject();
            line.addProperty("type", type);
            line.addProperty("t", System.currentTimeMillis());
            line.add("data", payload);
            recorder.write(Json.GSON.toJson(line));
            recorder.newLine();
        } catch (IOException e) {
            LOG.warn("record failed: " + e.getMessage());
        }
    }

    private synchronized void closeRecorder() {
        if (recorder != null) {
            try {
                recorder.close();
            } catch (IOException ignored) {
            }
            recorder = null;
        }
    }

    public JsonObject result() {
        return result;
    }

    public long endedAt() {
        return endedAt;
    }
}
