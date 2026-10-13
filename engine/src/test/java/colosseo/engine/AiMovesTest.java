package colosseo.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Seat options and record views of the XMage AI move recorder ({@link ReplayableAiPlayer}); playing and replaying
 * games is covered by the live replay tests (mtg-player adapters/colosseo/tests/test_replay_live.py).
 */
public class AiMovesTest {

    private static GameConfig parse(String seat1) {
        return GameConfig.parse(Json.parse("{\"seats\": [{\"type\": \"agent\", \"deck\": \"a\"}, " + seat1 + "]}"));
    }

    private static void refused(String seat1) {
        try {
            parse(seat1);
            fail("accepted " + seat1);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void aPlainXmageSeatRecordsNothing() {
        GameConfig.SeatConfig s = parse("{\"type\": \"xmage\", \"deck\": \"b\"}").seats.get(1);
        assertNull(s.aiMoves);
        assertNull(s.aiReplay);
        assertFalse(s.toJson().has("ai_moves"));
    }

    @Test
    public void recordAndReplayAreXmageSeatOptions() {
        GameConfig.SeatConfig rec = parse("{\"type\": \"xmage\", \"deck\": \"b\", \"ai_moves\": \"record\"}").seats.get(1);
        assertEquals("record", rec.aiMoves);
        assertEquals("record", rec.toJson().get("ai_moves").getAsString());
        GameConfig.SeatConfig rep = parse("{\"type\": \"xmage\", \"deck\": \"b\", \"ai_replay\": [{\"ev\": \"begin\"}]}").seats.get(1);
        assertEquals(1, rep.aiReplay.size());
        // the config line names how many lines were replayed, not the lines themselves
        assertEquals(1, rep.toJson().get("ai_replay_lines").getAsInt());
        assertFalse(rep.toJson().has("ai_replay"));
    }

    @Test
    public void misusedOptionsAreRefused() {
        refused("{\"type\": \"agent\", \"deck\": \"b\", \"ai_moves\": \"record\"}");
        refused("{\"type\": \"xmage\", \"deck\": \"b\", \"ai_moves\": \"yes\"}");
        refused("{\"type\": \"xmage\", \"deck\": \"b\", \"ai_replay\": {}}");
        refused("{\"type\": \"xmage\", \"deck\": \"b\", \"ai_moves\": \"record\", \"ai_replay\": []}");
    }

    @Test
    public void seatViewsDropAiMoveLines() {
        for (String type : new String[]{"ai_move", "ai_replay", "ai_replay_divergence"}) {
            JsonObject line = Json.parse("{\"type\": \"" + type + "\", \"data\": {\"seat\": 1, \"state\": {}}}");
            assertNull(Records.forSeat(line, 0));
            assertNull(Records.forSeat(line, 1));
        }
        assertTrue(Records.forSeat(Json.parse("{\"type\": \"result\", \"data\": {}}"), 0) != null);
    }
}
