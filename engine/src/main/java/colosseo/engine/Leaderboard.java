package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Elo ratings over the server's rated games, keyed by agent identity ({@code agent_id}, e.g. "my-agent@1.2").
 * <p>
 * Only games the rules engine finished are scored (wins, losses, draws); unfinished games (stopped, abandoned,
 * limits, errors) are counted but never change ratings. Ratings are recomputed in the order games ended, so
 * they are the same after a restart (rated games are reloaded from their records).
 */
final class Leaderboard {

    private static final Logger LOG = Logger.getLogger(Leaderboard.class);
    static final double K = 24.0;
    static final double BASE = 1500.0;

    private static final class Entry {
        final String gameId;
        final long endedAt;
        final JsonObject result;

        Entry(String gameId, long endedAt, JsonObject result) {
            this.gameId = gameId;
            this.endedAt = endedAt;
            this.result = result;
        }
    }

    private final List<Entry> games = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();

    synchronized void add(String gameId, long endedAt, JsonObject result) {
        if (ids.add(gameId)) {
            games.add(new Entry(gameId, endedAt, result));
        }
    }

    /**
     * Reloads rated games from the records in {@code gamesDir} (config line first, result line last).
     */
    void load(Path gamesDir) {
        if (!Files.isDirectory(gamesDir)) {
            return;
        }
        int n = 0;
        try (Stream<Path> files = Files.list(gamesDir)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".jsonl"))::iterator) {
                try {
                    String[] ends = Records.ends(f);
                    if (ends[0] == null || ends[1] == null) {
                        continue;
                    }
                    JsonObject config = Json.parse(ends[0]);
                    JsonObject last = Json.parse(ends[1]);
                    if (!"config".equals(Json.getString(config, "type", "")) || !"result".equals(Json.getString(last, "type", ""))
                            || !Json.getBool(config.getAsJsonObject("data"), "rated", false)) {
                        continue;
                    }
                    String id = Json.getString(config.getAsJsonObject("data"), "game_id",
                            f.getFileName().toString().replace(".jsonl", ""));
                    add(id, Json.getLong(last, "t", 0), last.getAsJsonObject("data"));
                    n++;
                } catch (IOException | RuntimeException e) {
                    LOG.debug("skipping record " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("can't read game records: " + e.getMessage());
        }
        if (n > 0) {
            LOG.info("leaderboard: " + n + " rated games loaded");
        }
    }

    synchronized JsonObject table() {
        List<Entry> ordered = new ArrayList<>(games);
        ordered.sort(Comparator.comparingLong((Entry e) -> e.endedAt).thenComparing(e -> e.gameId));
        Map<String, double[]> rating = new LinkedHashMap<>();       // agent -> {rating}
        Map<String, int[]> stats = new LinkedHashMap<>();           // agent -> {games, wins, losses, draws, unfinished, fallbacks, forfeits}
        for (Entry e : ordered) {
            JsonObject r = e.result;
            String[] agents = new String[2];
            JsonArray players = r.has("players") && r.get("players").isJsonArray() ? r.getAsJsonArray("players") : new JsonArray();
            for (JsonElement el : players) {
                JsonObject p = el.getAsJsonObject();
                int seat = Json.getInt(p, "seat", -1);
                if (seat == 0 || seat == 1) {
                    agents[seat] = Json.getString(p, "agent_id", Json.getString(p, "name", "seat " + seat));
                    int[] s = stats.computeIfAbsent(agents[seat], k -> new int[7]);
                    s[0]++;
                    if (p.has("fallbacks") && p.get("fallbacks").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> f : p.getAsJsonObject("fallbacks").entrySet()) {
                            s[5] += f.getValue().getAsInt();
                        }
                    }
                    rating.computeIfAbsent(agents[seat], k -> new double[]{BASE});
                }
            }
            if (agents[0] == null || agents[1] == null) {
                continue;
            }
            boolean finished = "finished".equals(Json.getString(r, "status", ""));
            Integer winner = r.has("winner_seat") && !r.get("winner_seat").isJsonNull() ? r.get("winner_seat").getAsInt() : null;
            boolean draw = Json.getBool(r, "draw", false);
            if (!finished || (winner == null && !draw)) {
                stats.get(agents[0])[4]++;
                stats.get(agents[1])[4]++;
                continue;
            }
            if (r.has("forfeit_seat") && !r.get("forfeit_seat").isJsonNull()) {
                int fs = r.get("forfeit_seat").getAsInt();
                if (fs == 0 || fs == 1) {
                    stats.get(agents[fs])[6]++;
                }
            }
            double scoreA = winner == null ? 0.5 : (winner == 0 ? 1.0 : 0.0);
            if (winner == null) {
                stats.get(agents[0])[3]++;
                stats.get(agents[1])[3]++;
            } else {
                stats.get(agents[winner])[1]++;
                stats.get(agents[1 - winner])[2]++;
            }
            if (agents[0].equals(agents[1])) {
                continue; // self-play counts in the totals but can't move a rating
            }
            double ra = rating.get(agents[0])[0];
            double rb = rating.get(agents[1])[0];
            double expectedA = 1.0 / (1.0 + Math.pow(10, (rb - ra) / 400.0));
            rating.get(agents[0])[0] = ra + K * (scoreA - expectedA);
            rating.get(agents[1])[0] = rb + K * ((1 - scoreA) - (1 - expectedA));
        }
        List<String> order = new ArrayList<>(rating.keySet());
        order.sort(Comparator.comparingDouble((String a) -> -rating.get(a)[0]).thenComparing(a -> a));
        JsonArray rows = new JsonArray();
        for (String agent : order) {
            int[] s = stats.get(agent);
            JsonObject row = new JsonObject();
            row.addProperty("agent_id", agent);
            row.addProperty("rating", Math.round(rating.get(agent)[0] * 10) / 10.0);
            row.addProperty("games", s[0]);
            row.addProperty("wins", s[1]);
            row.addProperty("losses", s[2]);
            row.addProperty("draws", s[3]);
            row.addProperty("unfinished", s[4]);
            row.addProperty("fallbacks", s[5]);
            row.addProperty("forfeits", s[6]);
            rows.add(row);
        }
        JsonObject o = new JsonObject();
        o.addProperty("rated_games", ordered.size());
        o.addProperty("k", K);
        o.addProperty("base", BASE);
        o.add("ratings", rows);
        return o;
    }
}
