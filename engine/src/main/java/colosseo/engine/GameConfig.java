package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of one game, parsed from the JSON body of {@code POST /api/games}.
 *
 * <pre>
 * {
 *   "seats": [
 *     {"name": "Alice", "type": "human", "deck": "fdn-azorius-fliers"},
 *     {"name": "MAD",   "type": "xmage", "deck": "fdn-gruul-stompy", "skill": 3}
 *   ],
 *   "starting_seat": -1,      // -1 = random
 *   "max_turns": 60,          // draw after this many turns
 *   "pace_ms": 0,             // slow games down for spectators (sleep per engine update)
 *   "seed": 123,              // optional, best effort (XMage shares one RNG across games)
 *   "record": true            // write a JSONL decision log under data/games
 * }
 * </pre>
 */
public final class GameConfig {

    public static final class SeatConfig {
        public String name;
        /**
         * human | agent | xmage (XMage's MAD simulation AI)
         */
        public String type;
        /**
         * deck id (see /api/decks), a path to a .dck file, or "sealed:SET" for a random sealed deck
         */
        public String deck;
        public int skill = 3;
        public boolean autoPassNoActions = true;
        /**
         * "all": stop at every priority where an action is possible.
         * "arena": stop only in own main phases, combat and when the opponent adds something to the stack.
         */
        public String stopPolicy;
        public boolean yieldAfterCast;
        public boolean autoPay = true;
        /**
         * seconds before the default action is taken (0 = wait forever)
         */
        public double timeoutS = 0;

        public boolean isBridge() {
            return "human".equals(type) || "agent".equals(type);
        }

        static SeatConfig parse(JsonObject o, int index) {
            SeatConfig s = new SeatConfig();
            s.type = Json.getString(o, "type", "agent");
            if (!List.of("human", "agent", "xmage").contains(s.type)) {
                throw new IllegalArgumentException("unknown seat type: " + s.type);
            }
            s.name = Json.getString(o, "name", defaultName(s.type, index));
            s.deck = Json.getString(o, "deck", null);
            s.skill = Math.max(1, Math.min(10, Json.getInt(o, "skill", 3)));
            boolean human = "human".equals(s.type);
            s.autoPassNoActions = Json.getBool(o, "auto_pass", true);
            s.stopPolicy = Json.getString(o, "stop_policy", human ? "arena" : "all");
            if (!s.stopPolicy.equals("arena") && !s.stopPolicy.equals("all")) {
                throw new IllegalArgumentException("stop_policy must be 'arena' or 'all'");
            }
            s.yieldAfterCast = Json.getBool(o, "yield_after_cast", human);
            s.autoPay = Json.getBool(o, "auto_pay", true);
            s.timeoutS = o.has("timeout_s") ? o.get("timeout_s").getAsDouble() : 0;
            return s;
        }

        private static String defaultName(String type, int index) {
            switch (type) {
                case "human":
                    return "Human " + (index + 1);
                case "xmage":
                    return "XMage AI " + (index + 1);
                default:
                    return "Agent " + (index + 1);
            }
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("type", type);
            o.addProperty("deck", deck);
            if (!isBridge()) {
                o.addProperty("skill", skill);
            } else {
                o.addProperty("auto_pass", autoPassNoActions);
                o.addProperty("stop_policy", stopPolicy);
                o.addProperty("yield_after_cast", yieldAfterCast);
                o.addProperty("auto_pay", autoPay);
                o.addProperty("timeout_s", timeoutS);
            }
            return o;
        }
    }

    public final List<SeatConfig> seats = new ArrayList<>();
    public int startingSeat = -1;
    public int maxTurns = 60;
    public int paceMs = 0;
    public Long seed;
    public boolean record = true;
    public String title;
    public int startingLife = 20;

    public static GameConfig parse(JsonObject o) {
        GameConfig c = new GameConfig();
        JsonElement seats = o.get("seats");
        if (seats == null || !seats.isJsonArray()) {
            throw new IllegalArgumentException("'seats' must be a list of two seats");
        }
        JsonArray arr = seats.getAsJsonArray();
        if (arr.size() != 2) {
            throw new IllegalArgumentException("exactly two seats are supported");
        }
        for (int i = 0; i < arr.size(); i++) {
            c.seats.add(SeatConfig.parse(arr.get(i).getAsJsonObject(), i));
        }
        c.startingSeat = Json.getInt(o, "starting_seat", -1);
        if (c.startingSeat < -1 || c.startingSeat > 1) {
            throw new IllegalArgumentException("starting_seat must be -1 (random), 0 or 1");
        }
        c.maxTurns = Json.getInt(o, "max_turns", 60);
        c.paceMs = Math.max(0, Json.getInt(o, "pace_ms", 0));
        if (o.has("seed") && !o.get("seed").isJsonNull()) {
            c.seed = o.get("seed").getAsLong();
        }
        c.record = Json.getBool(o, "record", true);
        c.title = Json.getString(o, "title", null);
        c.startingLife = Json.getInt(o, "starting_life", 20);
        return c;
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (SeatConfig s : seats) {
            arr.add(s.toJson());
        }
        o.add("seats", arr);
        o.addProperty("starting_seat", startingSeat);
        o.addProperty("max_turns", maxTurns);
        o.addProperty("pace_ms", paceMs);
        if (seed != null) {
            o.addProperty("seed", seed);
        }
        o.addProperty("record", record);
        o.addProperty("title", title);
        return o;
    }
}
