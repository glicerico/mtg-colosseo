package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Game records (JSONL: config, decision, action, fallback, result) and their per-seat views.
 * <p>
 * A full record holds both players' observations, so it must not be handed to an agent. The per-seat view keeps
 * only what that seat could see during the game: its own decisions (with its observations) and actions, the
 * opponent's actions as public notices (no hidden cards, no comments), the opponent's deck only as a hash, and
 * the result.
 */
final class Records {

    private Records() {
    }

    static Path file(Path dataDir, String gameId) {
        return dataDir.resolve("games").resolve(gameId + ".jsonl");
    }

    static List<String> read(Path file, Integer seat) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (seat == null) {
                    out.add(line);
                    continue;
                }
                JsonObject filtered = forSeat(Json.parse(line), seat);
                if (filtered != null) {
                    out.add(Json.GSON.toJson(filtered));
                }
            }
        }
        return out;
    }

    /**
     * One record line as seat {@code seat} may see it, or null to drop it.
     */
    static JsonObject forSeat(JsonObject line, int seat) {
        String type = Json.getString(line, "type", "");
        JsonObject data = line.has("data") && line.get("data").isJsonObject() ? line.getAsJsonObject("data") : new JsonObject();
        switch (type) {
            case "config": {
                JsonObject copy = line.deepCopy();
                JsonElement players = copy.getAsJsonObject("data").get("players");
                if (players != null && players.isJsonArray()) {
                    for (JsonElement p : players.getAsJsonArray()) {
                        if (p.isJsonObject() && Json.getInt(p.getAsJsonObject(), "seat", -1) != seat) {
                            p.getAsJsonObject().remove("decklist"); // the hash stays: same deck, same hash
                        }
                    }
                }
                return copy;
            }
            case "decision":
            case "fallback":
                return Json.getInt(data, "seat", -1) == seat ? line : null;
            case "action": {
                if (Json.getInt(data, "seat", -1) == seat) {
                    return line;
                }
                JsonObject pub = new JsonObject();
                pub.addProperty("seat", Json.getInt(data, "seat", -1));
                pub.addProperty("decision_id", Json.getLong(data, "decision_id", -1));
                pub.addProperty("kind", Json.getString(data, "kind", ""));
                pub.addProperty("summary", Json.getString(data, "public_summary", ""));
                JsonObject copy = new JsonObject();
                copy.addProperty("type", "action");
                copy.add("t", line.get("t"));
                copy.add("data", pub);
                return copy;
            }
            case "result":
                return line;
            default:
                return null;
        }
    }

    /**
     * First and last line of a record (config and, for finished games, result) without reading it all.
     */
    static String[] ends(Path file) throws IOException {
        String first;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            first = r.readLine();
        }
        String last = null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            long pos = len - 1;
            while (pos > 0) {
                raf.seek(pos);
                int c = raf.read();
                if (c == '\n' && pos < len - 1) {
                    break;
                }
                pos--;
            }
            int size = (int) Math.min(len - pos, 16 * 1024 * 1024);
            byte[] buf = new byte[size];
            raf.seek(pos <= 0 ? 0 : pos + 1);
            int n = raf.read(buf);
            if (n > 0) {
                last = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            }
        }
        return new String[]{first, last};
    }

    static JsonArray toArray(List<String> lines) {
        JsonArray arr = new JsonArray();
        for (String l : lines) {
            arr.add(Json.parse(l));
        }
        return arr;
    }
}
