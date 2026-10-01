package colosseo.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RedactionTest {

    private static Decision bottomChoice() {
        Decision d = new Decision(7, Decision.TARGET, UUID.randomUUID());
        d.prompt = "Select a card to put on the bottom of your library (Lightning Bolt, Shock)";
        d.addOption("c1", "Lightning Bolt");
        d.addOption("c2", "Shock");
        d.addOption("c3", "Grizzly Bears");
        d.markPrivate("c1", "a hidden card");
        d.markPrivate("c2", "a hidden card");
        return d;
    }

    @Test
    public void privateChoicesAreRedactedForOthers() {
        Decision d = bottomChoice();
        JsonObject action = Json.parse("{\"decision_id\": 7, \"choice\": \"c1\"}");
        assertEquals("Lightning Bolt", GameSession.summarize(d, action, true));
        String pub = GameSession.summarize(d, action, false);
        assertEquals("a hidden card", pub);
        assertFalse(pub.contains("Bolt"));
    }

    @Test
    public void multipleHiddenChoicesAreCounted() {
        Decision d = bottomChoice();
        JsonObject action = Json.parse("{\"decision_id\": 7, \"choices\": [\"c1\", \"c2\", \"c3\"]}");
        assertEquals("Lightning Bolt, Shock, Grizzly Bears", GameSession.summarize(d, action, true));
        assertEquals("Grizzly Bears, 2 x a hidden card", GameSession.summarize(d, action, false));
    }

    @Test
    public void unknownIdsAreNotEchoedToOthers() {
        Decision d = bottomChoice();
        String hiddenId = UUID.randomUUID().toString();
        JsonObject action = Json.parse("{\"decision_id\": 7, \"choices\": [\"c3\", \"" + hiddenId + "\"]}");
        String pub = GameSession.summarize(d, action, false);
        assertFalse(pub.contains(hiddenId));
        assertEquals("Grizzly Bears, another choice", pub);
    }

    @Test
    public void publicPromptNeverRepeatsThePrompt() {
        Decision d = bottomChoice();
        assertFalse(d.publicPrompt().contains("Bolt"));
        assertTrue(d.isPrivate("c1"));
        assertFalse(d.isPrivate("c3"));
        assertEquals("Grizzly Bears", d.label("c3", false));
    }

    @Test
    public void publicActionsKeepTheirLabels() {
        Decision d = new Decision(8, Decision.ATTACKERS, UUID.randomUUID());
        d.addOption("a1", "Grizzly Bears");
        JsonObject action = Json.parse("{\"decision_id\": 8, \"attackers\": [{\"attacker\": \"a1\"}]}");
        assertEquals("attack with Grizzly Bears", GameSession.summarize(d, action, false));
    }
}
