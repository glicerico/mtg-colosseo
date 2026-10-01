package colosseo.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccessPolicyTest {

    private static GameConfig config(boolean requireTokens) {
        JsonObject o = Json.parse("{\"seats\": [{\"type\": \"agent\", \"deck\": \"a\"}, {\"type\": \"agent\", \"deck\": \"b\"}],"
                + " \"require_tokens\": " + requireTokens + "}");
        return GameConfig.parse(o);
    }

    @Test
    public void defaultsDependOnBindAddress() {
        AccessPolicy local = AccessPolicy.forBind("127.0.0.1", null, null, null, List.of(), List.of());
        assertEquals(AccessPolicy.Mode.OPEN, local.mode);
        assertTrue(local.allowDeckPaths);
        AccessPolicy remote = AccessPolicy.forBind("0.0.0.0", null, null, null, List.of(), List.of());
        assertEquals(AccessPolicy.Mode.TOKENS, remote.mode);
        assertFalse(remote.allowDeckPaths);
    }

    @Test
    public void openModeAllowsEverythingForUnprotectedGames() {
        AccessPolicy p = AccessPolicy.forBind("localhost", null, null, null, List.of(), List.of());
        GameConfig c = config(false);
        assertTrue(p.canControlSeat(c, "seat-secret", null));
        assertTrue(p.canAdminister(c, "owner-secret", null));
        assertTrue(p.canCreate(null));
    }

    @Test
    public void tokensModeRequiresTheRightSecrets() {
        AccessPolicy p = AccessPolicy.forBind("0.0.0.0", null, null, null, List.of(), List.of());
        GameConfig c = config(false);
        assertFalse(p.canControlSeat(c, "seat-secret", null));
        assertFalse(p.canControlSeat(c, "seat-secret", ""));
        assertFalse(p.canControlSeat(c, "seat-secret", "owner-secret"));
        assertTrue(p.canControlSeat(c, "seat-secret", "seat-secret"));
        // revealing hands / stopping: owner token only, never a seat token
        assertFalse(p.canAdminister(c, "owner-secret", null));
        assertFalse(p.canAdminister(c, "owner-secret", "seat-secret"));
        assertTrue(p.canAdminister(c, "owner-secret", "owner-secret"));
    }

    @Test
    public void gamesCanOptIntoTokensOnAnOpenServer() {
        AccessPolicy p = AccessPolicy.forBind("127.0.0.1", null, null, null, List.of(), List.of());
        GameConfig c = config(true);
        assertTrue(p.isProtected(c));
        assertFalse(p.canControlSeat(c, "seat-secret", null));
        assertFalse(p.canAdminister(c, "owner-secret", null));
        assertTrue(p.canAdminister(c, "owner-secret", "owner-secret"));
    }

    @Test
    public void apiKeyGatesCreationAndActsAsMasterKey() {
        AccessPolicy p = AccessPolicy.forBind("0.0.0.0", AccessPolicy.Mode.TOKENS, "k3y", null, List.of(), List.of());
        assertTrue(p.apiKeyRequired());
        assertFalse(p.canCreate(null));
        assertFalse(p.canCreate("nope"));
        assertTrue(p.canCreate("k3y"));
        GameConfig c = config(false);
        assertTrue(p.canAdminister(c, "owner-secret", "k3y"));
        // the API key does not take over seats
        assertFalse(p.canControlSeat(c, "seat-secret", "k3y"));
    }

    @Test
    public void originChecks() {
        AccessPolicy p = AccessPolicy.forBind("127.0.0.1", null, null, null, List.of("http://localhost:5173"), List.of());
        assertTrue(p.originAllowed(null, "localhost:7070"));
        assertTrue(p.originAllowed("http://localhost:7070", "localhost:7070"));
        assertTrue(p.originAllowed("http://localhost:5173", "localhost:7070"));
        assertFalse(p.originAllowed("https://evil.example", "localhost:7070"));
        assertFalse(p.originAllowed("null", "localhost:7070"));
        AccessPolicy any = AccessPolicy.forBind("127.0.0.1", null, null, null, List.of("*"), List.of());
        assertTrue(any.originAllowed("https://evil.example", "localhost:7070"));
    }

    @Test
    public void openLoopbackServerRejectsForeignHostNames() {
        AccessPolicy p = AccessPolicy.forBind("127.0.0.1", null, null, null, List.of(), List.of("mybox"));
        assertTrue(p.hostAllowed("localhost:7070"));
        assertTrue(p.hostAllowed("127.0.0.1:7070"));
        assertTrue(p.hostAllowed("[::1]:7070"));
        assertTrue(p.hostAllowed("mybox:7070"));
        assertFalse(p.hostAllowed("rebind.evil.example:7070"));
        assertFalse(p.hostAllowed(null));
        // token mode relies on secrets, not host names
        AccessPolicy remote = AccessPolicy.forBind("0.0.0.0", null, null, null, List.of(), List.of());
        assertTrue(remote.hostAllowed("anything.example"));
    }

    @Test
    public void tokensAreLongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = AccessPolicy.newToken();
            assertEquals(32, t.length());
            assertTrue(seen.add(t));
        }
        assertFalse(AccessPolicy.matches(null, null));
        assertFalse(AccessPolicy.matches("", ""));
        assertTrue(AccessPolicy.matches("abc", "abc"));
        assertFalse(AccessPolicy.matches("abc", "abd"));
    }

    @Test
    public void parsesHostHeaders() {
        assertEquals("localhost", AccessPolicy.hostName("localhost:7070"));
        assertEquals("[::1]", AccessPolicy.hostName("[::1]:7070"));
        assertEquals("example.com", AccessPolicy.hostName("example.com"));
        assertEquals("http://localhost:5173", AccessPolicy.normalizeOrigin("HTTP://LocalHost:5173/"));
        assertEquals("https://a.example", AccessPolicy.normalizeOrigin("https://a.example:443"));
        assertNull(null);
    }
}
