package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecordsAndLeaderboardTest {

    private static JsonObject line(String json) {
        return Json.parse(json);
    }

    @Test
    public void perSeatRecordHidesTheOpponent() {
        JsonObject config = line("{\"type\":\"config\",\"t\":1,\"data\":{\"players\":["
                + "{\"seat\":0,\"deck_hash\":\"a\",\"decklist\":[\"1 X\"]},{\"seat\":1,\"deck_hash\":\"b\",\"decklist\":[\"1 Y\"]}]}}");
        JsonObject c = Records.forSeat(config, 0);
        JsonArray players = c.getAsJsonObject("data").getAsJsonArray("players");
        assertTrue(players.get(0).getAsJsonObject().has("decklist"));
        assertFalse(players.get(1).getAsJsonObject().has("decklist"));
        assertEquals("b", players.get(1).getAsJsonObject().get("deck_hash").getAsString());
        assertTrue(config.getAsJsonObject("data").getAsJsonArray("players").get(1).getAsJsonObject().has("decklist"));

        assertNull(Records.forSeat(line("{\"type\":\"decision\",\"data\":{\"seat\":1,\"state\":{}}}"), 0));
        assertEquals(0, Records.forSeat(line("{\"type\":\"decision\",\"data\":{\"seat\":0}}"), 0).getAsJsonObject("data").get("seat").getAsInt());
        assertNull(Records.forSeat(line("{\"type\":\"fallback\",\"data\":{\"seat\":1}}"), 0));

        JsonObject theirs = Records.forSeat(line("{\"type\":\"action\",\"t\":5,\"data\":{\"seat\":1,\"decision_id\":9,\"kind\":\"target\","
                + "\"action\":{\"choice\":\"x\",\"comment\":\"secret\"},\"summary\":\"Lightning Bolt\",\"public_summary\":\"a hidden card\"}}"), 0);
        JsonObject d = theirs.getAsJsonObject("data");
        assertEquals("a hidden card", d.get("summary").getAsString());
        assertFalse(d.has("action"));
        assertFalse(Json.GSON.toJson(theirs).contains("secret"));
        assertFalse(Json.GSON.toJson(theirs).contains("Bolt"));
    }

    private static JsonObject result(String status, Integer winner, boolean draw, String a0, String a1) {
        JsonObject r = new JsonObject();
        r.addProperty("status", status);
        r.addProperty("winner_seat", winner);
        r.addProperty("draw", draw);
        JsonArray players = new JsonArray();
        for (int i = 0; i < 2; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("seat", i);
            p.addProperty("agent_id", i == 0 ? a0 : a1);
            players.add(p);
        }
        r.add("players", players);
        return r;
    }

    @Test
    public void ratedResultsSurviveARestartWithoutGameRecords() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("colosseo-ledger");
        Leaderboard before = new Leaderboard();
        before.load(dir);
        assertTrue(before.addRated("g1", 1, result("finished", 0, false, "a@1", "b@1")));
        assertTrue(before.addRated("g2", 2, result("finished", null, true, "a@1", "b@1")));
        assertTrue(before.addRated("g3", 3, result("abandoned", null, false, "a@1", "b@1")));
        Leaderboard after = new Leaderboard(); // a restart: no game records, only the ledger
        after.load(dir);
        assertEquals(Json.GSON.toJson(before.table()), Json.GSON.toJson(after.table()));
        assertEquals(3, after.table().get("rated_games").getAsInt());
        assertEquals(0, after.table().get("unpersisted").getAsInt());
        // the same game found in the ledger and in an old record counts once
        after.add("g1", 1, result("finished", 0, false, "a@1", "b@1"));
        assertEquals(3, after.table().get("rated_games").getAsInt());
    }

    @Test
    public void aLedgerWriteFailureIsSurfaced() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("colosseo-ledger");
        java.nio.file.Files.createDirectory(dir.resolve("ratings.jsonl")); // not writable as a file
        Leaderboard board = new Leaderboard();
        board.load(dir);
        assertFalse(board.addRated("g1", 1, result("finished", 0, false, "a@1", "b@1")));
        assertEquals(1, board.table().get("unpersisted").getAsInt());
        assertEquals(1, board.table().get("rated_games").getAsInt());
    }

    @Test
    public void leaderboardScoresOnlyFinishedGames() {
        Leaderboard board = new Leaderboard();
        board.add("g1", 1, result("finished", 0, false, "a@1", "b@1"));
        board.add("g2", 2, result("terminated", null, false, "a@1", "b@1"));
        board.add("g3", 3, result("finished", null, true, "b@1", "a@1"));
        board.add("g1", 4, result("finished", 1, false, "a@1", "b@1")); // duplicate id: ignored
        JsonObject t = board.table();
        assertEquals(3, t.get("rated_games").getAsInt());
        JsonArray rows = t.getAsJsonArray("ratings");
        JsonObject top = rows.get(0).getAsJsonObject();
        assertEquals("a@1", top.get("agent_id").getAsString());
        assertEquals(1, top.get("wins").getAsInt());
        assertEquals(1, top.get("draws").getAsInt());
        assertEquals(1, top.get("unfinished").getAsInt());
        assertEquals(3, top.get("games").getAsInt());
        assertTrue(top.get("rating").getAsDouble() > 1500);
        assertEquals(3000.0, top.get("rating").getAsDouble() + rows.get(1).getAsJsonObject().get("rating").getAsDouble(), 0.2);
    }
}
