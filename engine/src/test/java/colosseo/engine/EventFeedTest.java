package colosseo.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What each browser view is told about an event ({@link EventFeed}); the events themselves come from live games
 * (python/tests/test_integration.py).
 */
public class EventFeedTest {

    private static JsonObject state(String json) {
        return Json.parse(json);
    }

    private static JsonObject move(String id, String from, String to) {
        return Json.parse("{\"kind\": \"move\", \"id\": \"" + id + "\", \"from\": \"" + from + "\", \"to\": \"" + to
                + "\", \"owner\": \"p2\", \"seq\": 1, \"log_at\": 0}");
    }

    @Test
    public void aViewSeesWhatItsStateShows() {
        Set<String> ids = EventFeed.visible(state("{\"players\": [{\"id\": \"p1\", \"hand\": [{\"id\": \"h1\"}], \"battlefield\": [{\"id\": \"b1\"}],"
                + " \"graveyard\": [{\"id\": \"g1\"}]}, {\"id\": \"p2\", \"battlefield\": [{\"id\": \"b2\"}]}],"
                + " \"stack\": [{\"id\": \"s1\", \"card_id\": \"c1\"}], \"exile\": [{\"id\": \"e1\"}, {\"id\": \"e2\", \"hidden\": true}]}"));
        assertEquals(Set.of("h1", "b1", "g1", "b2", "s1", "c1", "e1"), ids);
    }

    @Test
    public void aCardMovingBetweenZonesTheViewDoesntSeeLosesItsId() {
        EventFeed feed = new EventFeed();
        JsonObject empty = state("{\"players\": [{\"id\": \"p1\", \"hand\": []}, {\"id\": \"p2\"}]}");
        feed.seen("seat0", empty);
        // the opponent draws: library to their hand, seen neither before nor after
        JsonObject msg = feed.message(move("x1", "LIBRARY", "HAND"), empty, "seat0");
        JsonObject ev = msg.getAsJsonObject("event");
        assertFalse(ev.has("id"));
        assertTrue(ev.get("hidden").getAsBoolean());
        assertEquals("event", msg.get("type").getAsString());
        assertTrue(msg.has("state"));
    }

    @Test
    public void aCardTheViewSawBeforeOrAfterKeepsItsId() {
        EventFeed feed = new EventFeed();
        JsonObject withB2 = state("{\"players\": [{\"id\": \"p1\"}, {\"id\": \"p2\", \"battlefield\": [{\"id\": \"b2\"}]}]}");
        JsonObject withoutB2 = state("{\"players\": [{\"id\": \"p1\"}, {\"id\": \"p2\", \"battlefield\": []}]}");
        feed.seen("seat0", withB2);
        // returned to its owner's hand: it was on the battlefield
        JsonObject bounced = feed.message(move("b2", "BATTLEFIELD", "HAND"), withoutB2, "seat0").getAsJsonObject("event");
        assertEquals("b2", bounced.get("id").getAsString());
        assertFalse(bounced.has("hidden"));
        // played: on the battlefield after the move
        JsonObject played = feed.message(move("b2", "HAND", "BATTLEFIELD"), withB2, "seat0").getAsJsonObject("event");
        assertEquals("b2", played.get("id").getAsString());
    }

    @Test
    public void viewsAreTrackedApart() {
        EventFeed feed = new EventFeed();
        JsonObject revealed = state("{\"players\": [{\"id\": \"p1\"}, {\"id\": \"p2\", \"hand\": [{\"id\": \"h2\"}]}]}");
        JsonObject normal = state("{\"players\": [{\"id\": \"p1\"}, {\"id\": \"p2\"}]}");
        feed.seen("spectators-revealed", revealed);
        feed.seen("spectators", normal);
        JsonObject empty = state("{\"players\": [{\"id\": \"p1\"}, {\"id\": \"p2\", \"hand\": []}]}");
        // the card left p2's hand for its library: a spectator who saw hands saw it, the others didn't
        assertTrue(feed.message(move("h2", "HAND", "LIBRARY"), empty, "spectators-revealed").getAsJsonObject("event").has("id"));
        assertFalse(feed.message(move("h2", "HAND", "LIBRARY"), empty, "spectators").getAsJsonObject("event").has("id"));
    }

    @Test
    public void otherEventsAreSentAsTheyAre() {
        EventFeed feed = new EventFeed();
        JsonObject cast = Json.parse("{\"kind\": \"cast\", \"id\": \"s1\", \"card_id\": \"c1\", \"targets\": [\"b2\"], \"seq\": 3, \"log_at\": 9}");
        JsonObject ev = feed.message(cast, state("{\"players\": []}"), "seat0").getAsJsonObject("event");
        assertEquals(cast, ev);
    }
}
