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
    /**
     * Given to whoever created the game: allows revealing hands to a spectator and stopping the game.
     */
    public final String ownerToken = AccessPolicy.newToken();
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
    private volatile Game game;
    private volatile int finalTurn;
    private volatile JsonObject finalState;
    private volatile JsonObject finalStateRevealed;
    private final Map<Integer, JsonObject> finalSeatStates = new ConcurrentHashMap<>();
    private volatile String stopOutcome;
    private volatile String stopReason;
    private long seed;
    private int startingSeat = -1;
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
            seats.add(new Seat(i, config.seats.get(i), this, AccessPolicy.newToken()));
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
        // every game runs with a known seed (recorded in the config and the result) so it can be replayed
        seed = config.seed != null ? config.seed : new java.security.SecureRandom().nextLong() & Long.MAX_VALUE;
        RandomUtil.setSeed(seed);
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

        startingSeat = config.startingSeat >= 0 ? config.startingSeat : RandomUtil.nextInt(seats.size());
        game.setStartingPlayerId(seats.get(startingSeat).playerId);

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
        // only a game the rules engine ended by itself has a winner or is a draw; a stopped or crashed game
        // has an explicit non-terminal outcome so that it is never scored
        String outcome = stopOutcome != null ? stopOutcome : error != null ? "error" : "finished";
        boolean finished = "finished".equals(outcome);
        Integer winner = null;
        if (finished) {
            for (Seat seat : seats) {
                if (seat.player != null && seat.player.hasWon()) {
                    winner = seat.index;
                }
            }
        }
        JsonObject r = new JsonObject();
        r.addProperty("status", outcome);
        r.addProperty("winner_seat", winner);
        r.addProperty("winner", winner == null ? null : seats.get(winner).config().name);
        r.addProperty("draw", finished && winner == null);
        if (finished && winner == null && drawDeclared) {
            r.addProperty("reason", "turn_limit");
        } else if (!finished) {
            r.addProperty("reason", stopReason != null ? stopReason : error);
            r.addProperty("error", stopReason != null ? stopReason : error);
        }
        r.addProperty("turns", game == null ? 0 : game.getTurnNum());
        r.addProperty("decisions", decisionCount.get());
        r.addProperty("duration_s", (endedAt - startedAt) / 1000.0);
        r.addProperty("seed", seed);
        r.addProperty("starting_seat", startingSeat);
        JsonArray players = new JsonArray();
        for (Seat seat : seats) {
            JsonObject p = new JsonObject();
            p.addProperty("seat", seat.index);
            p.addProperty("name", seat.config().name);
            p.addProperty("type", seat.config().type);
            p.addProperty("deck", seat.config().deck);
            if (seat.player != null) {
                p.addProperty("life", seat.player.getLife());
                p.addProperty("won", finished && seat.player.hasWon());
                p.addProperty("lost", finished && seat.player.hasLost());
            }
            players.add(p);
        }
        r.add("players", players);
        result = r;
        status = outcome;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "game_over");
        msg.addProperty("game_id", id);
        msg.add("result", r);
        record("result", r);
        closeRecorder();

        // final views keep the usual hidden-information rules; they are cached for clients that attach later
        // (the XMage game itself is released below)
        for (Seat seat : seats) {
            JsonObject m = msg.deepCopy();
            JsonObject state = safeState(seat.playerId, false);
            if (state != null) {
                finalSeatStates.put(seat.index, state);
                m.add("state", state);
            }
            seat.broadcast(m);
        }
        finalState = safeState(null, false);
        finalStateRevealed = safeState(null, true);
        JsonObject specMsg = msg.deepCopy();
        JsonObject specRevealed = msg.deepCopy();
        if (finalState != null) {
            specMsg.add("state", finalState);
        }
        if (finalStateRevealed != null) {
            specRevealed.add("state", finalStateRevealed);
        }
        broadcastSpectators(specRevealed, specMsg);
        responder.shutdown();
        releaseGame();
        manager.onFinished(this);
    }

    private JsonObject safeState(UUID viewer, boolean revealAll) {
        if (game == null) {
            return null;
        }
        try {
            return StateView.build(game, viewer, revealAll, seatIndex);
        } catch (RuntimeException e) {
            LOG.debug("final state failed", e);
            return null;
        }
    }

    /**
     * Drops references to the XMage game and players: finished sessions stay listed but must not hold
     * megabytes of game state each.
     */
    private void releaseGame() {
        if (game != null) {
            finalTurn = game.getTurnNum();
        }
        game = null;
        for (Seat seat : seats) {
            seat.player = null;
        }
        seatByPlayer.clear();
        lastSeatState.clear();
        lastSpectatorState = null;
        lastSpectatorStateRevealed = null;
    }

    /**
     * Stops a running game (both players leave). The result has status "terminated" and is never a draw.
     */
    public void terminate() {
        stop("terminated", "terminated");
    }

    /**
     * Stops a running game with an explicit non-terminal outcome ("terminated", "abandoned", "timeout").
     */
    void stop(String outcome, String reason) {
        if (game == null || !"running".equals(status)) {
            return;
        }
        synchronized (this) {
            if (stopOutcome != null) {
                return;
            }
            stopOutcome = outcome;
            stopReason = reason;
        }
        addLog("Game stopped: " + reason);
        for (Seat seat : seats) {
            seat.cancelTimeout();
            if (seat.player != null) {
                seat.player.abort();
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Watchdog (timer thread): stops games past their deadline and games whose bridge seat has been
     * disconnected longer than the abandon timeout, so that abandoned games release their resources.
     */
    void checkLiveness(long now, double defaultAbandonTimeoutS) {
        if (!"running".equals(status)) {
            return;
        }
        if (config.deadlineS > 0 && now - startedAt > config.deadlineS * 1000) {
            stop("timeout", "deadline of " + fmt(config.deadlineS) + " s exceeded");
            return;
        }
        double abandon = config.abandonTimeoutS != null ? config.abandonTimeoutS : defaultAbandonTimeoutS;
        for (Seat seat : seats) {
            if (!seat.config().isBridge()) {
                continue;
            }
            if (seat.hasConnections()) {
                seat.lastSeenAt = now;
            } else if (abandon > 0 && now - seat.lastSeenAt > abandon * 1000) {
                stop("abandoned", "seat " + seat.index + " (" + seat.config().name + ") disconnected for more than "
                        + fmt(abandon) + " s");
                return;
            }
        }
    }

    private static String fmt(double seconds) {
        return seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds);
    }

    /**
     * Waits up to {@code millis} for the game thread to end (e.g. after {@link #terminate()}).
     */
    public void awaitEnd(long millis) {
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        // loop guard: an agent that keeps answering a payment prompt without progress gets cancelled
        String repeatKey = d.kind + "|" + d.prompt + "|" + d.options.keySet();
        if (Decision.MANA.equals(d.kind) && repeatKey.equals(seat.lastDecisionKey) && ++seat.repeatCount >= 25) {
            seat.repeatCount = 0;
            addLog(seat.config().name + ": payment made no progress, cancelled");
            JsonObject cancel = Decision.choiceAction("cancel");
            responder.execute(() -> d.responder.apply(cancel));
            return;
        }
        if (!repeatKey.equals(seat.lastDecisionKey)) {
            seat.lastDecisionKey = repeatKey;
            seat.repeatCount = 0;
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
            JsonObject revealed = note.deepCopy();
            revealed.addProperty("prompt", d.prompt);
            note.addProperty("prompt", d.publicPrompt());
            broadcastSpectators(revealed, note);
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
        // "stale": the answer is for a decision that is no longer pending (harmless, e.g. a resend after a
        // reconnect); "invalid": the decision is pending and the answer was rejected (the client should retry)
        String code = "stale";
        if (d == null) {
            err = "no decision pending for seat " + seat.index;
        } else if (did != d.id) {
            err = "stale decision_id " + did + " (pending: " + d.id + ")";
        } else {
            code = "invalid";
            try {
                err = d.responder.validate(action);
            } catch (RuntimeException e) {
                err = "invalid action: " + e.getMessage();
            }
        }
        if (err == null && !seat.pending.compareAndSet(d, null)) {
            code = "stale";
            err = "decision " + did + " was already answered";
        }
        if (err != null) {
            if (from != null) {
                from.sendError(err, did >= 0 ? did : null, code);
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

        // The acting seat (and spectators allowed to see hands) get the full acknowledgment; the opponent and
        // ordinary spectators get a redacted one that never names hidden cards. Comments are the agent's
        // private rationale unless the game makes them public.
        String comment = Json.getString(action, "comment", null);
        JsonObject full = ack(seat, d, summarize(d, action, true), comment);
        JsonObject pub = ack(seat, d, summarize(d, action, false), config.publicComments ? comment : null);
        seat.broadcast(full);
        for (Seat other : seats) {
            if (other != seat) {
                other.broadcast(pub);
            }
        }
        broadcastSpectators(full, pub);
        return null;
    }

    private static JsonObject ack(Seat seat, Decision d, String summary, String comment) {
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "action");
        ack.addProperty("seat", seat.index);
        ack.addProperty("decision_id", d.id);
        ack.addProperty("kind", d.kind);
        ack.addProperty("summary", summary);
        if (comment != null) {
            ack.addProperty("comment", comment);
        }
        return ack;
    }

    private JsonObject actionRecord(Seat seat, Decision d, JsonObject action) {
        JsonObject r = new JsonObject();
        r.addProperty("seat", seat.index);
        r.addProperty("decision_id", d.id);
        r.addProperty("kind", d.kind);
        r.add("action", action);
        return r;
    }

    /**
     * One-line description of an answer. {@code full=false} replaces private options (hidden cards) with
     * their public labels, collapsing repeats ("2 x a hidden card").
     */
    static String summarize(Decision d, JsonObject action, boolean full) {
        StringBuilder sb = new StringBuilder();
        if (action.has("choice") && action.get("choice").isJsonPrimitive()) {
            sb.append(d.label(Json.getString(action, "choice", ""), full));
        }
        if (action.has("choices") && action.get("choices").isJsonArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonElement el : action.getAsJsonArray("choices")) {
                ids.add(el.isJsonPrimitive() ? el.getAsString() : el.toString());
            }
            sb.append(joinLabels(d, ids, full));
        }
        if (action.has("amount")) {
            sb.append(Json.getString(action, "amount", ""));
        }
        if (action.has("amounts")) {
            sb.append(action.get("amounts").toString());
        }
        if (action.has("attackers") && action.get("attackers").isJsonArray()) {
            JsonArray arr = action.getAsJsonArray("attackers");
            if (arr.size() == 0) {
                sb.append("no attack");
            } else {
                List<String> ids = new ArrayList<>();
                for (JsonElement el : arr) {
                    ids.add(el.isJsonObject() ? Json.getString(el.getAsJsonObject(), "attacker", "") : el.getAsString());
                }
                sb.append("attack with ").append(joinLabels(d, ids, full));
            }
        }
        if (action.has("blocks") && action.get("blocks").isJsonArray()) {
            JsonArray arr = action.getAsJsonArray("blocks");
            sb.append(arr.size() == 0 ? "no blocks" : arr.size() + " block(s)");
        }
        return sb.toString();
    }

    private static String joinLabels(Decision d, List<String> ids, boolean full) {
        List<String> labels = new ArrayList<>();
        Map<String, Integer> hidden = new java.util.LinkedHashMap<>();
        for (String optionId : ids) {
            if (!full && d.isPrivate(optionId)) {
                hidden.merge(d.label(optionId, false), 1, Integer::sum);
            } else {
                labels.add(d.label(optionId, full));
            }
        }
        for (Map.Entry<String, Integer> e : hidden.entrySet()) {
            labels.add(e.getValue() == 1 ? e.getKey() : e.getValue() + " x " + e.getKey());
        }
        return String.join(", ", labels);
    }

    // ---------------------------------------------------------------------------------------------
    // clients
    // ---------------------------------------------------------------------------------------------

    void attachPlayer(Seat seat, Connection c) {
        c.seat = seat;
        seat.connections.add(c);
        seat.lastSeenAt = System.currentTimeMillis();
        JsonObject hello = hello(seat.index);
        hello.addProperty("role", "player");
        JsonObject state = result != null ? finalSeatStates.get(seat.index) : lastSeatState.get(seat.index);
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
        hello.addProperty("reveal", c.revealAll);
        hello.addProperty("may_reveal", c.mayReveal);
        JsonObject state = result != null ? (c.revealAll ? finalStateRevealed : finalState)
                : (c.revealAll ? lastSpectatorStateRevealed : lastSpectatorState);
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
            if (seat.connections.remove(c)) {
                seat.lastSeenAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * A spectator switched hands on or off: send the matching view right away.
     */
    void spectatorChanged(Connection c) {
        JsonObject state = result != null ? (c.revealAll ? finalStateRevealed : finalState)
                : (c.revealAll ? lastSpectatorStateRevealed : lastSpectatorState);
        if (state == null) {
            state = tryBuildState(null, c.revealAll);
        }
        if (state != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "state");
            msg.add("state", state);
            c.send(msg);
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
        broadcastSpectators(msg, msg);
    }

    /**
     * Sends {@code revealed} to spectators allowed to see hidden information and {@code normal} to the others.
     */
    private void broadcastSpectators(JsonObject revealed, JsonObject normal) {
        if (spectators.isEmpty()) {
            return;
        }
        String revealedText = Json.GSON.toJson(revealed);
        String normalText = revealed == normal ? revealedText : Json.GSON.toJson(normal);
        for (Connection c : spectators) {
            c.send(c.revealAll ? revealedText : normalText);
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
        Game g = game;
        o.addProperty("turn", g == null ? finalTurn : g.getTurnNum());
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
        o.addProperty("seed", seed);
        o.addProperty("starting_seat_actual", startingSeat);
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
            recorder.flush();
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
