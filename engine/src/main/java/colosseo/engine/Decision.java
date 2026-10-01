package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A pending decision for one seat: what to decide, the legal options and how to apply an answer.
 * <p>
 * Every choice the rules engine needs from a player (priority, targets, modes, combat, mana, ...)
 * is exposed as one Decision. Humans (web UI) and agents (SDK) answer the same objects.
 */
public final class Decision {

    // decision kinds (stable protocol names)
    public static final String PRIORITY = "priority";
    public static final String MULLIGAN = "mulligan";
    public static final String YES_NO = "yes_no";
    public static final String TARGET = "target";
    public static final String ABILITY = "choose_ability";
    public static final String MODE = "choose_mode";
    public static final String CHOICE = "choose_choice";
    public static final String AMOUNT = "amount";
    public static final String MULTI_AMOUNT = "multi_amount";
    public static final String PILE = "choose_pile";
    public static final String MANA = "pay_mana";
    public static final String ATTACKERS = "declare_attackers";
    public static final String BLOCKERS = "declare_blockers";
    public static final String TRIGGER_ORDER = "trigger_order";

    /**
     * Validates and applies answers. {@link #validate} runs on the caller's thread and must not touch the game;
     * {@link #apply} hands the answer to the waiting game thread.
     */
    public interface Responder {
        /**
         * @return null if the answer is acceptable, otherwise a human readable error (decision stays pending)
         */
        String validate(JsonObject action);

        void apply(JsonObject action);
    }

    public final long id;
    public final String kind;
    public final UUID playerId;
    public String prompt = "";
    /**
     * Options, keyed by option id. Each value is the JSON description sent to the client.
     */
    public final Map<String, JsonObject> options = new LinkedHashMap<>();
    /**
     * Kind specific extra fields merged into the JSON (min/max, attackers, piles, ...).
     */
    public final JsonObject extra = new JsonObject();
    /**
     * Labels shown to opponents and ordinary spectators for options that refer to hidden information
     * (cards in hand or library, face-down cards). Options not listed here are public.
     */
    public final Map<String, String> privateOptions = new HashMap<>();
    public Responder responder;
    /**
     * Answer used by timeouts / default policy.
     */
    public JsonObject defaultAction;

    // filled when published
    public JsonObject state;
    public JsonArray newLog;
    public final long createdAtMs = System.currentTimeMillis();

    public Decision(long id, String kind, UUID playerId) {
        this.id = id;
        this.kind = kind;
        this.playerId = playerId;
    }

    public JsonObject addOption(String optionId, String label) {
        JsonObject o = new JsonObject();
        o.addProperty("id", optionId);
        o.addProperty("label", label);
        options.put(optionId, o);
        return o;
    }

    public boolean hasOption(String optionId) {
        return optionId != null && options.containsKey(optionId);
    }

    /**
     * Marks an option as private: other players and ordinary spectators only see {@code publicLabel}.
     */
    public void markPrivate(String optionId, String publicLabel) {
        privateOptions.put(optionId, publicLabel);
    }

    public boolean isPrivate(String optionId) {
        return optionId != null && privateOptions.containsKey(optionId);
    }

    /**
     * Label of an option for the acting seat ({@code full}) or for everyone else.
     */
    public String label(String optionId, boolean full) {
        if (!full && privateOptions.containsKey(optionId)) {
            return privateOptions.get(optionId);
        }
        JsonObject opt = options.get(optionId);
        if (opt == null) {
            // e.g. later ids of a multi-target answer: never echo raw object ids to other players
            return full ? optionId : "another choice";
        }
        return Json.getString(opt, "label", optionId);
    }

    /**
     * What opponents and ordinary spectators are told while this decision is pending. Prompts can name
     * hidden cards ("Put Shock on the bottom?"), so they only see the kind of decision.
     */
    public String publicPrompt() {
        switch (kind) {
            case PRIORITY:
                return "has priority";
            case MULLIGAN:
                return "is deciding whether to mulligan";
            case TARGET:
                return "is choosing";
            case ABILITY:
                return "is choosing an ability";
            case MODE:
                return "is choosing a mode";
            case CHOICE:
                return "is making a choice";
            case TRIGGER_ORDER:
                return "is ordering triggered abilities";
            case AMOUNT:
            case MULTI_AMOUNT:
                return "is choosing a number";
            case PILE:
                return "is choosing a pile";
            case MANA:
                return "is paying mana";
            case ATTACKERS:
                return "is declaring attackers";
            case BLOCKERS:
                return "is declaring blockers";
            default:
                return "is deciding";
        }
    }

    public static JsonObject choiceAction(String optionId) {
        JsonObject a = new JsonObject();
        a.addProperty("choice", optionId);
        return a;
    }

    public JsonObject toJson(String gameId, int seat) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "decision");
        o.addProperty("game_id", gameId);
        o.addProperty("seat", seat);
        o.addProperty("decision_id", id);
        o.addProperty("kind", kind);
        o.addProperty("prompt", prompt);
        JsonArray arr = new JsonArray();
        options.values().forEach(arr::add);
        o.add("options", arr);
        for (Map.Entry<String, com.google.gson.JsonElement> e : extra.entrySet()) {
            if (!e.getKey().startsWith("_")) { // internal bookkeeping
                o.add(e.getKey(), e.getValue());
            }
        }
        if (defaultAction != null) {
            o.add("default", defaultAction);
        }
        if (state != null) {
            o.add("state", state);
        }
        if (newLog != null) {
            o.add("log", newLog);
        }
        return o;
    }
}
