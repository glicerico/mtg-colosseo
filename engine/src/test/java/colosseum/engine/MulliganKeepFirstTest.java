package colosseum.engine;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The seat option {@code mulligan_keep_first} ({@link KeepFirstLondonMulligan}); the mulligans themselves are played in
 * the integration tests (python/tests/test_integration.py).
 */
public class MulliganKeepFirstTest {

    private static GameConfig.SeatConfig seat(String json) {
        return GameConfig.parse(Json.parse("{\"seats\": [" + json + ", {\"type\": \"agent\", \"deck\": \"b\"}]}")).seats.get(0);
    }

    @Test
    public void humanSeatsKeepFirstByDefault() {
        assertTrue(seat("{\"type\": \"human\", \"deck\": \"a\"}").mulliganKeepFirst);
        assertFalse(seat("{\"type\": \"agent\", \"deck\": \"a\"}").mulliganKeepFirst);
        assertFalse(seat("{\"type\": \"xmage\", \"deck\": \"a\"}").mulliganKeepFirst);
        assertTrue(seat("{\"type\": \"agent\", \"deck\": \"a\", \"mulligan_keep_first\": true}").mulliganKeepFirst);
        assertFalse(seat("{\"type\": \"human\", \"deck\": \"a\", \"mulligan_keep_first\": false}").mulliganKeepFirst);
    }

    @Test
    public void configsNameTheOptionOnlyWhenItIsNotTheDefault() {
        // so that the configs of seats that don't set it (and what agents are sent about them) stay as they were
        assertFalse(seat("{\"type\": \"human\", \"deck\": \"a\"}").toJson().has("mulligan_keep_first"));
        assertFalse(seat("{\"type\": \"agent\", \"deck\": \"a\"}").toJson().has("mulligan_keep_first"));
        assertFalse(seat("{\"type\": \"xmage\", \"deck\": \"a\"}").toJson().has("mulligan_keep_first"));
        assertTrue(seat("{\"type\": \"agent\", \"deck\": \"a\", \"mulligan_keep_first\": true}").toJson()
                .get("mulligan_keep_first").getAsBoolean());
        assertFalse(seat("{\"type\": \"human\", \"deck\": \"a\", \"mulligan_keep_first\": false}").toJson()
                .get("mulligan_keep_first").getAsBoolean());
    }

    @Test(expected = IllegalArgumentException.class)
    public void xmageSeatsKeepXmagesOrder() {
        seat("{\"type\": \"xmage\", \"deck\": \"a\", \"mulligan_keep_first\": true}");
    }

    @Test
    public void copiesKeepTheKeepFirstPlayers() {
        // AI simulations copy the game, and with it its mulligan
        KeepFirstLondonMulligan m = new KeepFirstLondonMulligan(0);
        UUID human = UUID.randomUUID();
        m.keepFirst(human);
        KeepFirstLondonMulligan copy = m.copy();
        assertTrue(copy.isKeepFirst(human));
        assertFalse(copy.isKeepFirst(UUID.randomUUID()));
    }
}
