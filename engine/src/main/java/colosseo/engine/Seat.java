package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.constants.PhaseStep;
import mage.game.Game;
import mage.game.stack.StackObject;
import mage.players.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One side of a game: its player, the controller configuration and the connected clients.
 */
public final class Seat {

    public final int index;
    private final GameConfig.SeatConfig config;
    private final GameSession session;
    public final String token;

    Player player;
    UUID playerId;

    final AtomicReference<Decision> pending = new AtomicReference<>();
    final List<Connection> connections = new CopyOnWriteArrayList<>();

    // game thread only
    private int logCursor = 0;

    private volatile int passTurnNumber = -1;
    private volatile String info;
    private volatile ScheduledFuture<?> timeoutTask;

    // queued multi-target answers ("choices": [a, b, c]) consumed by the following target dialogs
    private final Deque<String> choiceQueue = new ArrayDeque<>();
    private String choiceQueuePrompt;
    private final Set<String> choiceQueueExpected = new LinkedHashSet<>();

    Seat(int index, GameConfig.SeatConfig config, GameSession session, String token) {
        this.index = index;
        this.config = config;
        this.session = session;
        this.token = token;
    }

    public GameConfig.SeatConfig config() {
        return config;
    }

    public GameSession session() {
        return session;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean isBridge() {
        return player instanceof BridgePlayer;
    }

    // --- priority stops -------------------------------------------------------------------------

    public boolean passTurnActive(Game game) {
        return passTurnNumber == game.getTurnNum();
    }

    public void startPassTurn(Game game) {
        passTurnNumber = game.getTurnNum();
    }

    /**
     * Whether a bridge seat wants to be asked at this priority (it has legal actions).
     */
    public boolean shouldStop(Game game, boolean hasActions) {
        if (!hasActions) {
            return true; // auto_pass=false: every priority is a decision
        }
        if ("all".equals(config.stopPolicy)) {
            return true;
        }
        StackObject top = game.getStack().getFirstOrNull();
        if (top != null) {
            // respond to what the opponent put on the stack, let our own things resolve
            return !playerId.equals(top.getControllerId());
        }
        boolean myTurn = playerId.equals(game.getActivePlayerId());
        PhaseStep step = game.getTurnStepType();
        boolean combat = !game.getCombat().getAttackers().isEmpty();
        if (myTurn) {
            return step == PhaseStep.PRECOMBAT_MAIN
                    || step == PhaseStep.POSTCOMBAT_MAIN
                    || (combat && step == PhaseStep.DECLARE_BLOCKERS);
        }
        return (combat && (step == PhaseStep.DECLARE_ATTACKERS || step == PhaseStep.DECLARE_BLOCKERS))
                || step == PhaseStep.END_TURN;
    }

    public void setStopPolicy(String policy) {
        if ("all".equals(policy) || "arena".equals(policy)) {
            config.stopPolicy = policy;
        }
    }

    public void setYieldAfterCast(boolean value) {
        config.yieldAfterCast = value;
    }

    // --- informational messages from the engine ------------------------------------------------

    void setInfo(String message) {
        this.info = message;
    }

    String takeInfo() {
        String i = info;
        info = null;
        return i;
    }

    // --- log ------------------------------------------------------------------------------------

    JsonArray takeNewLog(List<JsonObject> log) {
        JsonArray arr = new JsonArray();
        synchronized (log) {
            for (int i = logCursor; i < log.size(); i++) {
                arr.add(log.get(i));
            }
            logCursor = log.size();
        }
        return arr;
    }

    // --- connections ----------------------------------------------------------------------------

    void broadcast(JsonObject message) {
        String text = Json.GSON.toJson(message);
        for (Connection c : connections) {
            c.send(text);
        }
    }

    boolean hasConnections() {
        return !connections.isEmpty();
    }

    // --- timeouts -------------------------------------------------------------------------------

    void scheduleTimeout(Decision d) {
        cancelTimeout();
        if (config.timeoutS <= 0 || d.defaultAction == null) {
            return;
        }
        long ms = (long) (config.timeoutS * 1000);
        timeoutTask = session.timers().schedule(() -> {
            if (pending.get() == d) {
                JsonObject action = d.defaultAction.deepCopy();
                action.addProperty("decision_id", d.id);
                action.addProperty("comment", "timeout: default action");
                session.submit(this, action, null);
            }
        }, ms, TimeUnit.MILLISECONDS);
    }

    void cancelTimeout() {
        ScheduledFuture<?> t = timeoutTask;
        if (t != null) {
            t.cancel(false);
            timeoutTask = null;
        }
    }

    // --- multi target answers -------------------------------------------------------------------

    synchronized void queueChoices(String prompt, List<String> rest, Set<String> expectedChosen) {
        choiceQueue.clear();
        choiceQueue.addAll(rest);
        choiceQueuePrompt = prompt;
        choiceQueueExpected.clear();
        choiceQueueExpected.addAll(expectedChosen);
    }

    /**
     * Answers a target dialog from the queue when it continues a previous multi-target answer.
     *
     * @return the answer to apply, or null if the decision must be published
     */
    synchronized JsonObject nextQueuedChoice(Decision d) {
        if (choiceQueue.isEmpty()) {
            return null;
        }
        if (!Decision.TARGET.equals(d.kind) || !d.prompt.equals(choiceQueuePrompt)) {
            choiceQueue.clear();
            return null;
        }
        Set<String> chosen = new LinkedHashSet<>();
        if (d.extra.has("chosen")) {
            for (JsonElement el : d.extra.getAsJsonArray("chosen")) {
                chosen.add(el.getAsString());
            }
        }
        if (!chosen.containsAll(choiceQueueExpected)) {
            // a different dialog (e.g. the previous target was completed automatically)
            choiceQueue.clear();
            return null;
        }
        String next = choiceQueue.poll();
        if ("done".equals(next)) {
            if (!d.hasOption("done") || chosen.isEmpty()) {
                choiceQueue.clear();
                return null;
            }
        } else if (!d.hasOption(next) || chosen.contains(next)) {
            choiceQueue.clear();
            return null;
        } else {
            choiceQueueExpected.add(next);
        }
        return Decision.choiceAction(next);
    }
}
