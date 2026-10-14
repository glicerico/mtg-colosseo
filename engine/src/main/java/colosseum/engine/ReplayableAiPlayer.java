package colosseum.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.common.PassAbility;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.MultiAmountType;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayerControllableProxy;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.util.MultiAmountMessage;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * XMage's MAD AI ({@link ComputerPlayerControllableProxy}, what an {@code xmage} seat normally plays) with its moves
 * recorded in one run and applied in another, so that a game against XMage's AI can be replayed from its record
 * instead of re-run from its seed.
 * <p>
 * The AI's own play is not reproducible from a seed: it searches with a time limit, and it iterates over objects
 * in the order of their random ids. So in <b>record</b> mode every decision the game asks of this player
 * (priority, mana payment, attackers, blockers, targets, choices, amounts, modes, ...) is logged as a
 * {@code begin} line (with the whole game state in {@link StableIds stable names}), the moves the AI made
 * inside it (activated abilities with their modes, targets and costs, passes, declared attackers and blockers)
 * as {@code act} lines, and its outcome (return value, chosen targets, choice) as an {@code end} line. The AI's
 * thinking draws its random numbers from its own generator, so the game's generator (shuffles, coin flips) only
 * advances on what the rules do, exactly as when the moves are applied without thinking.
 * <p>
 * In <b>replay</b> mode the same calls are answered from the record: each decision must be the next one in the
 * record, with the same state; its recorded moves are made again on the live objects and its recorded outcome is
 * returned. The first difference (another decision, another state, a move that can't be made, an object that
 * doesn't exist) is the divergence: it is reported and the AI plays live from there on.
 * <p>
 * Without either mode the seat is the plain {@code ComputerPlayerControllableProxy}; this class is only used
 * when a seat asks for it ({@code "ai_moves": "record"} or {@code "ai_replay": [...]}).
 */
public class ReplayableAiPlayer extends ComputerPlayerControllableProxy {

    private static final Logger LOG = Logger.getLogger(ReplayableAiPlayer.class);
    private static final ThreadLocal<Random> SEEDED = seededField();

    /**
     * State shared by this player and its copies (XMage copies players for bookmarks and rollbacks).
     */
    static final class Context {
        final StableIds ids;
        final int seat;
        /** record mode: where lines go (null in replay mode) */
        final Consumer<JsonObject> sink;
        /** replay mode: the recorded lines still to apply (null in record mode) */
        final Deque<JsonObject> replay;
        /** replay mode: where the divergence is reported the moment it happens (its place among the game's lines) */
        final Consumer<JsonObject> notes;
        final Random aiRandom;

        // record mode: open frames (true = a decision of this player, false = a move it makes inside one)
        final Deque<Boolean> frames = new ArrayDeque<>();
        final Deque<Random> outer = new ArrayDeque<>();
        int seq;

        // replay mode
        int total;
        int decisions;
        int moves;
        int states;
        String divergence;
        JsonObject divergenceDetail;
        Integer divergenceSeq;

        Context(StableIds ids, int seat, long seed, Consumer<JsonObject> sink, JsonArray replay, Consumer<JsonObject> notes) {
            this.ids = ids;
            this.seat = seat;
            this.sink = sink;
            this.notes = notes;
            this.aiRandom = new Random(seed * 31 + 7 + seat);
            if (replay != null) {
                this.replay = new ArrayDeque<>();
                for (JsonElement e : replay) {
                    this.replay.add(e.getAsJsonObject());
                }
                this.total = this.replay.size();
            } else {
                this.replay = null;
            }
        }

        boolean recording() {
            return sink != null;
        }

        boolean replaying() {
            return replay != null && divergence == null;
        }

        JsonObject summary() {
            JsonObject o = new JsonObject();
            o.addProperty("seat", seat);
            o.addProperty("recorded_lines", total);
            o.addProperty("applied_lines", total - (replay == null ? 0 : replay.size()));
            o.addProperty("decisions_forced", decisions);
            o.addProperty("moves_made", moves);
            o.addProperty("states_matched", states);
            o.addProperty("fully_replayed", divergence == null && replay != null && replay.isEmpty());
            o.addProperty("divergence", divergence);
            o.addProperty("divergence_seq", divergenceSeq);
            if (divergenceDetail != null) {
                o.add("divergence_detail", divergenceDetail);
            }
            return o;
        }
    }

    final Context ctx;

    public ReplayableAiPlayer(String name, RangeOfInfluence range, int skill, Context ctx) {
        super(name, range, skill);
        this.ctx = ctx;
    }

    public ReplayableAiPlayer(final ReplayableAiPlayer player) {
        super(player);
        this.ctx = player.ctx;
    }

    @Override
    public ReplayableAiPlayer copy() {
        return new ReplayableAiPlayer(this);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Random> seededField() {
        try {
            // the per-game generator of the patched RandomUtil (scripts/xmage-patches/0001-reproducible-seeds.patch)
            Field f = RandomUtil.class.getDeclaredField("seeded");
            f.setAccessible(true);
            return (ThreadLocal<Random>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("RandomUtil without the Colosseum seed patch", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // recording
    // ---------------------------------------------------------------------------------------------

    /**
     * The game's state for a decision: the objects in stable names and where the game's random generator is (the
     * first number a copy of it would draw; the generator itself is not advanced).
     */
    private JsonObject state(Game game) {
        JsonObject state = ctx.ids.state(game);
        state.addProperty("rng", rngFingerprint(SEEDED.get()));
        return state;
    }

    static String rngFingerprint(Random random) {
        if (random == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeObject(random);
            }
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                return Long.toHexString(((Random) in.readObject()).nextLong());
            }
        } catch (java.io.IOException | ClassNotFoundException e) {
            return "unavailable";
        }
    }

    private void emit(JsonObject line) {
        line.addProperty("seat", ctx.seat);
        line.addProperty("n", ctx.seq++);
        ctx.sink.accept(line);
    }

    /** a decision the game asks of this player, as opposed to one the AI asks itself while thinking */
    private boolean recordable(Game game) {
        return ctx.recording() && !game.isSimulation() && (ctx.frames.isEmpty() || !ctx.frames.peek());
    }

    /** a move the AI makes directly inside one of its recorded decisions */
    private boolean recordableMove(Game game) {
        return ctx.recording() && !game.isSimulation() && !ctx.frames.isEmpty() && ctx.frames.peek();
    }

    private <T> T recordDecision(Game game, String kind, JsonObject info, Supplier<T> live, Function<T, JsonObject> outcome) {
        JsonObject begin = info == null ? new JsonObject() : info;
        begin.addProperty("ev", "begin");
        begin.addProperty("kind", kind);
        JsonObject state = state(game);
        begin.add("state", state);
        begin.addProperty("state_sha", DeckLibrary.sha256(Json.GSON.toJson(state)));
        emit(begin);
        Random previous = SEEDED.get();
        ctx.outer.push(previous);
        ctx.frames.push(true);
        SEEDED.set(ctx.aiRandom); // thinking does not advance the game's generator
        T result;
        try {
            result = live.get();
        } finally {
            ctx.frames.pop();
            SEEDED.set(ctx.outer.pop());
        }
        JsonObject end = outcome.apply(result);
        end.addProperty("ev", "end");
        end.addProperty("kind", kind);
        emit(end);
        return result;
    }

    private <T> T recordMove(String kind, JsonObject info, Supplier<T> live, Function<T, JsonElement> ret) {
        info.addProperty("ev", "act");
        info.addProperty("kind", kind);
        emit(info);
        Random previous = SEEDED.get();
        ctx.frames.push(false);
        SEEDED.set(ctx.outer.peek()); // the move itself runs on the game's generator
        T result;
        try {
            result = live.get();
        } finally {
            ctx.frames.pop();
            SEEDED.set(previous);
        }
        JsonObject end = new JsonObject();
        end.addProperty("ev", "act_end");
        end.addProperty("kind", kind);
        end.add("ret", ret.apply(result));
        emit(end);
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // replaying
    // ---------------------------------------------------------------------------------------------

    private void diverge(String reason, JsonObject detail) {
        if (ctx.divergence != null) {
            return;
        }
        JsonObject next = ctx.replay.peek();
        ctx.divergence = reason;
        ctx.divergenceSeq = next == null ? null : Json.getInt(next, "n", -1);
        ctx.divergenceDetail = detail;
        LOG.info("AI replay of seat " + ctx.seat + " diverged: " + reason);
        if (ctx.notes != null) {
            JsonObject note = new JsonObject();
            note.addProperty("seat", ctx.seat);
            note.addProperty("divergence", reason);
            note.addProperty("divergence_seq", ctx.divergenceSeq);
            ctx.notes.accept(note);
        }
    }

    private static JsonObject detail(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof JsonElement) {
                o.add((String) kv[i], (JsonElement) v);
            } else {
                o.addProperty((String) kv[i], v == null ? null : String.valueOf(v));
            }
        }
        return o;
    }

    /**
     * Takes the recorded begin line of the decision the game asks now, or diverges.
     */
    private JsonObject expectBegin(Game game, String kind, JsonObject info) {
        JsonObject next = ctx.replay.peek();
        if (next == null) {
            diverge("record_exhausted", detail("live_kind", kind));
            return null;
        }
        if (!"begin".equals(Json.getString(next, "ev", "")) || !kind.equals(Json.getString(next, "kind", ""))) {
            diverge("other_decision", detail("live_kind", kind, "recorded", next));
            return null;
        }
        if (info != null) {
            for (Map.Entry<String, JsonElement> e : info.entrySet()) {
                if (!e.getValue().equals(next.get(e.getKey()))) {
                    diverge("decision_context_differs", detail("kind", kind, "field", e.getKey(),
                            "recorded", next.get(e.getKey()), "live", e.getValue()));
                    return null;
                }
            }
        }
        // the full recorded state, or only its hash (a replay request may leave the states out to stay small)
        JsonObject live = state(game);
        JsonElement recorded = next.get("state");
        boolean same = recorded != null ? live.equals(recorded)
                : DeckLibrary.sha256(Json.GSON.toJson(live)).equals(Json.getString(next, "state_sha", ""));
        if (!same) {
            diverge("state_mismatch", detail("kind", kind, "recorded_state", recorded, "live_state", live));
            return null;
        }
        ctx.states++;
        ctx.decisions++;
        return ctx.replay.poll();
    }

    /**
     * Makes the recorded moves of the current decision again, up to its end line, and returns that line (null on
     * divergence).
     */
    private JsonObject applyMoves(Game game, String kind) {
        while (ctx.replaying()) {
            JsonObject next = ctx.replay.peek();
            if (next == null) {
                diverge("record_exhausted", detail("in_kind", kind));
                return null;
            }
            String ev = Json.getString(next, "ev", "");
            if ("end".equals(ev)) {
                if (!kind.equals(Json.getString(next, "kind", ""))) {
                    diverge("other_decision_end", detail("live_kind", kind, "recorded", next));
                    return null;
                }
                return ctx.replay.poll();
            }
            if (!"act".equals(ev)) {
                diverge("decision_not_raised", detail("in_kind", kind, "recorded", next));
                return null;
            }
            ctx.replay.poll();
            JsonElement ret = makeMove(game, kind, next);
            if (ret == null) {
                diverge("move_not_made", detail("move", next)); // no-op when the move already said why
                return null;
            }
            JsonObject end = ctx.replay.peek();
            if (end == null || !"act_end".equals(Json.getString(end, "ev", ""))) {
                diverge("decision_not_raised", detail("after_move", next, "recorded", end));
                return null;
            }
            if (!ret.equals(end.get("ret"))) {
                diverge("move_result_differs", detail("move", next, "recorded_ret", end.get("ret"), "live_ret", ret));
                return null;
            }
            ctx.replay.poll();
            ctx.moves++;
        }
        return null;
    }

    private JsonElement makeMove(Game game, String parentKind, JsonObject move) {
        String kind = Json.getString(move, "kind", "");
        switch (kind) {
            case "pass":
                super.pass(game);
                return Json.prim("ok");
            case "attack": {
                UUID attacker = resolve(game, move, "attacker");
                UUID defender = resolve(game, move, "defender");
                if (attacker == null || defender == null) {
                    return null;
                }
                super.declareAttacker(attacker, defender, game, Json.getBool(move, "undo", true));
                return Json.prim("ok");
            }
            case "block": {
                UUID defender = resolve(game, move, "defender");
                UUID blocker = resolve(game, move, "blocker");
                UUID attacker = resolve(game, move, "attacker");
                if (defender == null || blocker == null || attacker == null) {
                    return null;
                }
                super.declareBlocker(defender, blocker, attacker, game, Json.getBool(move, "undo", true));
                return Json.prim("ok");
            }
            case "activate": {
                if ("PassAbility".equals(Json.getString(move.getAsJsonObject("ability"), "class", ""))) {
                    // the AI's "pass" action: not an ability of any object
                    return new com.google.gson.JsonPrimitive(super.activateAbility(new PassAbility(), game));
                }
                ActivatedAbility ability = rebuild(game, move);
                if (ability == null) {
                    return null;
                }
                if ("priority".equals(parentKind)) {
                    // what ComputerPlayer6.act does with a simulated ability before activating it
                    for (Target target : ability.getTargets()) {
                        for (UUID id : target.getTargets()) {
                            target.updateTarget(id, game);
                            if (!target.isNotTarget()) {
                                game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability, ability.getControllerId()));
                            }
                        }
                    }
                }
                return new com.google.gson.JsonPrimitive(super.activateAbility(ability, game));
            }
            default:
                diverge("unknown_move", detail("move", move));
                return null;
        }
    }

    private UUID resolve(Game game, JsonObject o, String field) {
        String key = Json.getString(o, field, null);
        UUID id = ctx.ids.resolve(game, key);
        if (id == null) {
            diverge("unknown_object", detail("field", field, "key", key, "line", o));
        }
        return id;
    }

    // ---------------------------------------------------------------------------------------------
    // abilities: described in stable names, rebuilt on the live objects
    // ---------------------------------------------------------------------------------------------

    private static List<Ability> candidates(Game game, UUID sourceId) {
        List<Ability> out = new ArrayList<>();
        Permanent perm = game.getPermanent(sourceId);
        if (perm != null) {
            out.addAll(perm.getAbilities(game));
            return out;
        }
        Card card = game.getCard(sourceId);
        if (card != null) {
            out.addAll(card.getAbilities(game));
        }
        return out;
    }

    private JsonArray targetsJson(Game game, Target target) {
        JsonArray a = new JsonArray();
        for (UUID id : target.getTargets()) {
            JsonArray t = new JsonArray();
            t.add(ctx.ids.key(game, id));
            t.add(target.getTargetAmount(id));
            a.add(t);
        }
        return a;
    }

    private JsonObject describe(Game game, Ability ability) {
        JsonObject o = new JsonObject();
        o.addProperty("source", ctx.ids.key(game, ability.getSourceId()));
        o.addProperty("class", ability.getClass().getSimpleName());
        o.addProperty("rule", ability.getRule());
        int index = -1;
        List<Ability> cands = candidates(game, ability.getSourceId());
        for (int i = 0; i < cands.size(); i++) {
            if (cands.get(i).getId().equals(ability.getId())) {
                index = i;
                break;
            }
        }
        o.addProperty("index", index);
        Modes modes = ability.getModes();
        List<UUID> modeIds = new ArrayList<>(modes.keySet());
        JsonArray selected = new JsonArray();
        JsonArray targets = new JsonArray();
        for (UUID modeId : modes.getSelectedModes()) {
            selected.add(modeIds.indexOf(modeId));
            Mode mode = modes.get(modeId);
            JsonArray perMode = new JsonArray();
            if (mode != null) {
                for (Target t : mode.getTargets()) {
                    JsonObject tj = new JsonObject();
                    tj.add("chosen", targetsJson(game, t));
                    tj.addProperty("skip", t.isSkipChoice());
                    perMode.add(tj);
                }
            }
            targets.add(perMode);
        }
        o.add("modes", selected);
        o.addProperty("preselected", modes.isPreselected());
        o.add("targets", targets);
        JsonArray costTargets = new JsonArray();
        for (Target t : ability.getCosts().getTargets()) {
            costTargets.add(targetsJson(game, t));
        }
        o.add("cost_targets", costTargets);
        o.addProperty("mana", ability.getManaCostsToPay().getText());
        return o;
    }

    private Ability findAbility(Game game, JsonObject d) {
        UUID src = resolve(game, d, "source");
        if (src == null) {
            return null;
        }
        String cls = Json.getString(d, "class", "");
        String rule = Json.getString(d, "rule", "");
        List<Ability> cands = candidates(game, src);
        int index = Json.getInt(d, "index", -1);
        if (index >= 0 && index < cands.size()) {
            Ability a = cands.get(index);
            if (a.getClass().getSimpleName().equals(cls) && a.getRule().equals(rule)) {
                return a;
            }
        }
        for (Ability a : cands) {
            if (a.getClass().getSimpleName().equals(cls) && a.getRule().equals(rule)) {
                return a;
            }
        }
        diverge("unknown_ability", detail("ability", d));
        return null;
    }

    private boolean addTargets(Game game, Target target, JsonArray chosen, Ability source, boolean skipEvent) {
        for (JsonElement e : chosen) {
            JsonArray t = e.getAsJsonArray();
            String key = t.get(0).isJsonNull() ? null : t.get(0).getAsString();
            UUID id = ctx.ids.resolve(game, key);
            if (id == null) {
                diverge("unknown_object", detail("key", key, "target", chosen));
                return false;
            }
            if (target.getTargets().contains(id)) {
                continue;
            }
            if (target instanceof TargetAmount) {
                target.addTarget(id, t.get(1).getAsInt(), source, game, skipEvent);
            } else if (target.isNotTarget() && !skipEvent) {
                target.add(id, game);
            } else {
                target.addTarget(id, source, game, skipEvent);
            }
        }
        return true;
    }

    private ActivatedAbility rebuild(Game game, JsonObject move) {
        JsonObject d = move.getAsJsonObject("ability");
        Ability found = findAbility(game, d);
        if (found == null) {
            return null;
        }
        if (!(found instanceof ActivatedAbility)) {
            diverge("not_activated", detail("ability", d));
            return null;
        }
        ActivatedAbility a = (ActivatedAbility) found.copy();
        Modes modes = a.getModes();
        List<UUID> modeIds = new ArrayList<>(modes.keySet());
        JsonArray selected = d.getAsJsonArray("modes");
        JsonArray targets = d.getAsJsonArray("targets");
        if (modeIds.size() > 1) {
            modes.clearSelectedModes();
            for (JsonElement e : selected) {
                UUID modeId = modeIds.get(e.getAsInt());
                modes.addSelectedMode(modeId);
                modes.setActiveMode(modeId);
            }
        }
        modes.setPreselected(Json.getBool(d, "preselected", false));
        List<UUID> live = modes.getSelectedModes();
        for (int m = 0; m < targets.size() && m < live.size(); m++) {
            Mode mode = modes.get(live.get(m));
            JsonArray perMode = targets.get(m).getAsJsonArray();
            for (int i = 0; i < perMode.size() && i < mode.getTargets().size(); i++) {
                JsonObject tj = perMode.get(i).getAsJsonObject();
                Target t = mode.getTargets().get(i);
                t.clearChosen();
                if (!addTargets(game, t, tj.getAsJsonArray("chosen"), a, true)) {
                    return null;
                }
                t.setSkipChoice(Json.getBool(tj, "skip", false));
            }
        }
        JsonArray costTargets = d.getAsJsonArray("cost_targets");
        for (int i = 0; i < costTargets.size() && i < a.getCosts().getTargets().size(); i++) {
            Target t = a.getCosts().getTargets().get(i);
            t.clearChosen();
            if (!addTargets(game, t, costTargets.get(i).getAsJsonArray(), a, true)) {
                return null;
            }
        }
        JsonObject rebuilt = describe(game, a);
        for (String field : new String[]{"modes", "targets", "cost_targets", "mana"}) {
            if (!rebuilt.get(field).equals(d.get(field))) {
                diverge("ability_not_rebuilt", detail("field", field, "recorded", d, "rebuilt", rebuilt));
                return null;
            }
        }
        return a;
    }

    // ---------------------------------------------------------------------------------------------
    // the generic decision wrapper
    // ---------------------------------------------------------------------------------------------

    /**
     * Records, replays or simply plays one decision. {@code forced} turns the recorded end line into the result.
     */
    private <T> T decision(Game game, String kind, JsonObject info, Supplier<T> live, Function<T, JsonObject> outcome,
                           Runnable prelude, Function<JsonObject, T> forced, Supplier<T> fallback) {
        if (game.isSimulation()) {
            return live.get();
        }
        if (recordable(game)) {
            return recordDecision(game, kind, info, live, outcome);
        }
        if (ctx.replaying() && (ctx.frames.isEmpty())) {
            JsonObject begin = expectBegin(game, kind, info);
            if (begin == null) {
                return live.get();
            }
            if (prelude != null) {
                prelude.run();
            }
            JsonObject end = applyMoves(game, kind);
            if (end == null) {
                return fallback.get();
            }
            T result = forced.apply(end);
            if (result == null && ctx.divergence != null) {
                return fallback.get();
            }
            return result;
        }
        return live.get();
    }

    private <T> T simple(Game game, String kind, JsonObject info, Supplier<T> live, Function<T, JsonObject> outcome,
                         Function<JsonObject, T> forced) {
        return decision(game, kind, info, live, outcome, null, forced, live);
    }

    private static JsonObject ret(Object value) {
        JsonObject o = new JsonObject();
        if (value instanceof Boolean) {
            o.addProperty("ret", (Boolean) value);
        } else if (value instanceof Number) {
            o.addProperty("ret", (Number) value);
        } else {
            o.addProperty("ret", value == null ? null : String.valueOf(value));
        }
        return o;
    }

    private JsonObject sourceInfo(Game game, Ability source) {
        JsonObject o = new JsonObject();
        if (source != null) {
            o.addProperty("source", ctx.ids.key(game, source.getSourceId()));
            o.addProperty("source_rule", source.getRule());
        }
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // moves made inside a decision
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        if (!recordableMove(game) || ability == null) {
            return super.activateAbility(ability, game);
        }
        JsonObject info = new JsonObject();
        info.add("ability", describe(game, ability));
        return recordMove("activate", info, () -> super.activateAbility(ability, game), com.google.gson.JsonPrimitive::new);
    }

    @Override
    public void pass(Game game) {
        if (!recordableMove(game)) {
            super.pass(game);
            return;
        }
        recordMove("pass", new JsonObject(), () -> {
            super.pass(game);
            return "ok";
        }, Json::prim);
    }

    @Override
    public void declareAttacker(UUID attackerId, UUID defenderId, Game game, boolean allowUndo) {
        if (!recordableMove(game)) {
            super.declareAttacker(attackerId, defenderId, game, allowUndo);
            return;
        }
        JsonObject info = new JsonObject();
        info.addProperty("attacker", ctx.ids.key(game, attackerId));
        info.addProperty("defender", ctx.ids.key(game, defenderId));
        info.addProperty("undo", allowUndo);
        recordMove("attack", info, () -> {
            super.declareAttacker(attackerId, defenderId, game, allowUndo);
            return "ok";
        }, Json::prim);
    }

    @Override
    public void declareBlocker(UUID defenderId, UUID blockerId, UUID attackerId, Game game, boolean allowUndo) {
        if (!recordableMove(game)) {
            super.declareBlocker(defenderId, blockerId, attackerId, game, allowUndo);
            return;
        }
        JsonObject info = new JsonObject();
        info.addProperty("defender", ctx.ids.key(game, defenderId));
        info.addProperty("blocker", ctx.ids.key(game, blockerId));
        info.addProperty("attacker", ctx.ids.key(game, attackerId));
        info.addProperty("undo", allowUndo);
        recordMove("block", info, () -> {
            super.declareBlocker(defenderId, blockerId, attackerId, game, allowUndo);
            return "ok";
        }, Json::prim);
    }

    // ---------------------------------------------------------------------------------------------
    // decisions with moves
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean priority(Game game) {
        return decision(game, "priority", null, () -> super.priority(game), ReplayableAiPlayer::ret,
                () -> {
                    // what ComputerPlayer7.priority does around its moves
                    game.getState().setPriorityPlayerId(playerId);
                    game.firePriorityEvent(playerId);
                },
                end -> Json.getBool(end, "ret", false),
                () -> {
                    if (!isPassed()) {
                        super.pass(game);
                    }
                    return false;
                });
    }

    @Override
    public boolean playMana(Ability abilityToCast, ManaCost unpaid, String promptText, Game game) {
        JsonObject info = sourceInfo(game, abilityToCast);
        info.addProperty("unpaid", unpaid.getText());
        return decision(game, "play_mana", info, () -> super.playMana(abilityToCast, unpaid, promptText, game),
                ReplayableAiPlayer::ret, null, end -> Json.getBool(end, "ret", false), () -> false);
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        decision(game, "select_attackers", null, () -> {
                    super.selectAttackers(game, attackingPlayerId);
                    return "ok";
                }, r -> new JsonObject(),
                () -> {
                    // what ComputerPlayer6.declareAttackers does before it declares
                    game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, playerId));
                    game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, playerId, playerId));
                },
                end -> "ok", () -> "ok");
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        int movesBefore = ctx.moves;
        String r = decision(game, "select_blockers", null, () -> {
                    super.selectBlockers(source, game, defendingPlayerId);
                    return "ok";
                }, x -> new JsonObject(),
                () -> {
                    // what ComputerPlayer6.declareBlockers does before it declares
                    game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, playerId));
                    game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_BLOCKERS, playerId, playerId));
                },
                end -> "forced", () -> "ok");
        if ("forced".equals(r) && ctx.moves > movesBefore) {
            game.getPlayers().resetPassed(); // ComputerPlayer6.declareBlockers after any block
        }
    }

    // ---------------------------------------------------------------------------------------------
    // decisions without moves: the outcome is the return value and what was chosen
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean chooseMulligan(Game game) {
        return simple(game, "mulligan", null, () -> super.chooseMulligan(game), ReplayableAiPlayer::ret,
                end -> Json.getBool(end, "ret", false));
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, Ability source, Game game) {
        JsonObject info = sourceInfo(game, source);
        info.addProperty("message", message);
        return simple(game, "use", info, () -> super.chooseUse(outcome, message, source, game), ReplayableAiPlayer::ret,
                end -> Json.getBool(end, "ret", false));
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        JsonObject info = sourceInfo(game, source);
        info.addProperty("message", message);
        return simple(game, "use", info,
                () -> super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game),
                ReplayableAiPlayer::ret, end -> Json.getBool(end, "ret", false));
    }

    @Override
    public int chooseReplacementEffect(Map<String, String> effectsMap, Map<String, MageObject> objectsMap, Game game) {
        List<String> texts = new ArrayList<>(effectsMap.values());
        return simple(game, "replacement_effect", null,
                () -> super.chooseReplacementEffect(effectsMap, objectsMap, game),
                r -> {
                    JsonObject o = ret(r);
                    o.addProperty("text", r >= 0 && r < texts.size() ? texts.get(r) : null);
                    return o;
                },
                end -> {
                    int i = texts.indexOf(Json.getString(end, "text", null));
                    if (i < 0) {
                        diverge("unknown_choice", detail("end", end, "live", texts.toString()));
                        return null;
                    }
                    return i;
                });
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        JsonObject info = new JsonObject();
        info.addProperty("message", choice.getMessage());
        return simple(game, "choice", info, () -> super.choose(outcome, choice, game),
                r -> {
                    JsonObject o = ret(r);
                    o.addProperty("key", choice.isKeyChoice() ? choice.getChoiceKey() : null);
                    o.addProperty("value", choice.isKeyChoice() ? null : choice.getChoice());
                    return o;
                },
                end -> {
                    if (choice.isKeyChoice()) {
                        String key = Json.getString(end, "key", null);
                        if (key != null) {
                            choice.setChoiceByKey(key);
                        }
                    } else {
                        String value = Json.getString(end, "value", null);
                        if (value != null) {
                            choice.setChoice(value);
                        }
                    }
                    return Json.getBool(end, "ret", false);
                });
    }

    private boolean targetDecision(Game game, String kind, Target target, Ability source, Supplier<Boolean> live) {
        JsonObject info = sourceInfo(game, source);
        info.add("before", targetsJson(game, target));
        return simple(game, kind, info, live,
                r -> {
                    JsonObject o = ret(r);
                    o.add("chosen", targetsJson(game, target));
                    return o;
                },
                end -> {
                    if (!targetsJson(game, target).equals(info.get("before"))) {
                        diverge("target_state_differs", detail("kind", kind));
                        return null;
                    }
                    if (!addTargets(game, target, end.getAsJsonArray("chosen"), source, false)) {
                        return null;
                    }
                    if (!targetsJson(game, target).equals(end.get("chosen"))) {
                        diverge("target_not_rebuilt", detail("kind", kind, "recorded", end.get("chosen"),
                                "live", targetsJson(game, target)));
                        return null;
                    }
                    return Json.getBool(end, "ret", false);
                });
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        return targetDecision(game, "choose_target", target, source, () -> super.choose(outcome, target, source, game));
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        return targetDecision(game, "choose_target", target, source, () -> super.choose(outcome, target, source, game, options));
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        return targetDecision(game, "target", target, source, () -> super.chooseTarget(outcome, target, source, game));
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return targetDecision(game, "choose_card", target, source, () -> super.choose(outcome, cards, target, source, game));
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return targetDecision(game, "target_card", target, source, () -> super.chooseTarget(outcome, cards, target, source, game));
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        return targetDecision(game, "target_amount", target, source, () -> super.chooseTargetAmount(outcome, target, source, game));
    }

    private String triggerKey(Game game, TriggeredAbility a) {
        return ctx.ids.key(game, a.getSourceId()) + "|" + a.getRule();
    }

    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        List<String> keys = new ArrayList<>();
        for (TriggeredAbility a : abilities) {
            keys.add(triggerKey(game, a));
        }
        JsonObject info = new JsonObject();
        JsonArray options = new JsonArray();
        keys.forEach(options::add);
        info.add("options", options);
        return simple(game, "trigger_order", info, () -> super.chooseTriggeredAbility(abilities, game),
                r -> {
                    JsonObject o = new JsonObject();
                    int i = abilities.indexOf(r);
                    String key = i < 0 ? null : keys.get(i);
                    o.addProperty("key", key);
                    // which of several equal triggers (they are interchangeable)
                    o.addProperty("ordinal", i < 0 ? -1 : (int) keys.subList(0, i).stream().filter(k -> k.equals(key)).count());
                    return o;
                },
                end -> {
                    String key = Json.getString(end, "key", null);
                    int ordinal = Json.getInt(end, "ordinal", 0);
                    for (int i = 0; i < keys.size(); i++) {
                        if (keys.get(i).equals(key) && ordinal-- == 0) {
                            return abilities.get(i);
                        }
                    }
                    diverge("unknown_trigger", detail("end", end, "live", options));
                    return null;
                });
    }

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        JsonObject info = sourceInfo(game, source);
        info.addProperty("min", min);
        info.addProperty("max", max);
        return simple(game, "announce_x", info, () -> super.announceX(min, max, message, game, source, isManaPay),
                ReplayableAiPlayer::ret, end -> Json.getInt(end, "ret", min));
    }

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        JsonObject info = sourceInfo(game, source);
        info.addProperty("min", min);
        info.addProperty("max", max);
        return simple(game, "amount", info, () -> super.getAmount(min, max, message, source, game),
                ReplayableAiPlayer::ret, end -> Json.getInt(end, "ret", min));
    }

    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(Outcome outcome, List<MultiAmountMessage> messages,
                                                                 int totalMin, int totalMax, MultiAmountType type, Game game) {
        return simple(game, "multi_amount", null,
                () -> super.getMultiAmountWithIndividualConstraints(outcome, messages, totalMin, totalMax, type, game),
                r -> {
                    JsonObject o = new JsonObject();
                    JsonArray a = new JsonArray();
                    r.forEach(a::add);
                    o.add("ret", a);
                    return o;
                },
                end -> {
                    List<Integer> out = new ArrayList<>();
                    end.getAsJsonArray("ret").forEach(e -> out.add(e.getAsInt()));
                    return out;
                });
    }

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        List<Mode> all = new ArrayList<>(modes.values());
        return simple(game, "mode", sourceInfo(game, source), () -> super.chooseMode(modes, source, game),
                r -> ret(r == null ? -1 : all.indexOf(r)),
                end -> {
                    int i = Json.getInt(end, "ret", -1);
                    return i < 0 || i >= all.size() ? null : all.get(i);
                });
    }

    @Override
    public boolean choosePile(Outcome outcome, String message, List<? extends Card> pile1, List<? extends Card> pile2, Game game) {
        return simple(game, "pile", null, () -> super.choosePile(outcome, message, pile1, pile2, game),
                ReplayableAiPlayer::ret, end -> Json.getBool(end, "ret", false));
    }

    /** not forced: the AI picks by the cards' castable abilities; the choice is checked against the record */
    @Override
    public SpellAbility chooseAbilityForCast(Card card, Game game, boolean noMana) {
        return simple(game, "ability_for_cast", null, () -> super.chooseAbilityForCast(card, game, noMana),
                r -> {
                    JsonObject o = new JsonObject();
                    o.add("ability", r == null ? null : describe(game, r));
                    return o;
                },
                end -> {
                    SpellAbility r = super.chooseAbilityForCast(card, game, noMana);
                    JsonElement live = r == null ? com.google.gson.JsonNull.INSTANCE : describe(game, r);
                    if (!live.equals(end.get("ability"))) {
                        diverge("other_ability_for_cast", detail("recorded", end.get("ability"), "live", live));
                    }
                    return r;
                });
    }
}
