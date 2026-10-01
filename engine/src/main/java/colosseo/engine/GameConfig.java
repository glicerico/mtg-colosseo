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
 *   "seed": 123,              // optional (generated and recorded when absent); best effort, XMage shares one RNG
 *   "record": true,           // write a JSONL decision log under data/games
 *   "public_comments": false, // show agents' comments to the opponent and every spectator
 *   "require_tokens": false,  // require seat tokens even when the server runs in open mode
 *   "abandon_timeout_s": 600, // stop the game when a bridge seat stays disconnected this long (0 = never)
 *   "deadline_s": 0,          // stop the game after this many seconds (0 = no deadline)
 *   "max_decisions": 10000,   // stop the game (status "limit") after this many decisions (0 = no limit)
 *   "max_record_mb": 100,     // stop the game (status "limit") when its record grows past this size
 *   "turn_limit_result": "draw", // "draw" or "void" (status "turn_limit", not scored) at max_turns
 *   "rated": false            // counts for the server's leaderboard
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
        /**
         * chess-clock style budget of thinking time for the whole game (0 = none); running out forfeits
         */
        public double timeBankS = 0;
        /**
         * who plays this seat, for records and ratings (e.g. "my-agent@1.2"; optional)
         */
        public String agentId;
        /**
         * content hash of the agent's code, if the client provides one
         */
        public String agentHash;

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
            s.timeBankS = Math.max(0, o.has("time_bank_s") && !o.get("time_bank_s").isJsonNull() ? o.get("time_bank_s").getAsDouble() : 0);
            s.agentId = Json.getString(o, "agent_id", null);
            s.agentHash = Json.getString(o, "agent_hash", null);
            if (s.agentId != null && (s.agentId.isBlank() || s.agentId.length() > 200)) {
                throw new IllegalArgumentException("agent_id must be 1-200 characters");
            }
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
            o.addProperty("agent_id", agentId());
            if (agentHash != null) {
                o.addProperty("agent_hash", agentHash);
            }
            if (!isBridge()) {
                o.addProperty("skill", skill);
            } else {
                o.addProperty("auto_pass", autoPassNoActions);
                o.addProperty("stop_policy", stopPolicy);
                o.addProperty("yield_after_cast", yieldAfterCast);
                o.addProperty("auto_pay", autoPay);
                o.addProperty("timeout_s", timeoutS);
                o.addProperty("time_bank_s", timeBankS);
            }
            return o;
        }

        /**
         * Identity used in records and ratings: the declared agent_id, "xmage:N" for XMage's AI, or the name.
         */
        public String agentId() {
            if (agentId != null) {
                return agentId;
            }
            return "xmage".equals(type) ? "xmage:" + skill : type + ":" + name;
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
    public boolean publicComments = false;
    public boolean requireTokens = false;
    /**
     * null = server default
     */
    public Double abandonTimeoutS;
    public double deadlineS = 0;
    public int maxDecisions = 10000;
    public double maxRecordMb = 100;
    public String turnLimitResult = "draw";
    public boolean rated = false;

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
        c.publicComments = Json.getBool(o, "public_comments", false);
        c.requireTokens = Json.getBool(o, "require_tokens", false);
        if (o.has("abandon_timeout_s") && !o.get("abandon_timeout_s").isJsonNull()) {
            c.abandonTimeoutS = Math.max(0, o.get("abandon_timeout_s").getAsDouble());
        }
        c.deadlineS = o.has("deadline_s") && !o.get("deadline_s").isJsonNull() ? Math.max(0, o.get("deadline_s").getAsDouble()) : 0;
        c.maxDecisions = Math.max(0, Json.getInt(o, "max_decisions", 10000));
        c.maxRecordMb = o.has("max_record_mb") && !o.get("max_record_mb").isJsonNull() ? Math.max(0, o.get("max_record_mb").getAsDouble()) : 100;
        c.turnLimitResult = Json.getString(o, "turn_limit_result", "draw");
        if (!c.turnLimitResult.equals("draw") && !c.turnLimitResult.equals("void")) {
            throw new IllegalArgumentException("turn_limit_result must be 'draw' or 'void'");
        }
        c.rated = Json.getBool(o, "rated", false);
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
        o.addProperty("starting_life", startingLife);
        o.addProperty("public_comments", publicComments);
        o.addProperty("require_tokens", requireTokens);
        o.addProperty("abandon_timeout_s", abandonTimeoutS);
        o.addProperty("deadline_s", deadlineS);
        o.addProperty("max_decisions", maxDecisions);
        o.addProperty("max_record_mb", maxRecordMb);
        o.addProperty("turn_limit_result", turnLimitResult);
        o.addProperty("rated", rated);
        return o;
    }
}
