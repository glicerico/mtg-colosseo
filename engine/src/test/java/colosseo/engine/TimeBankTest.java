package colosseo.engine;

import com.google.gson.JsonObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The time bank must charge only the time a decision was really pending, whatever the thread interleaving.
 */
public class TimeBankTest {

    private static Path dir;
    private static GameManager manager;

    @BeforeClass
    public static void setUp() throws Exception {
        dir = Files.createTempDirectory("colosseo-test");
        manager = new GameManager(new DeckLibrary(dir), dir);
    }

    @AfterClass
    public static void tearDown() {
        manager.timers().shutdownNow();
    }

    private static GameSession session(double bank) {
        GameConfig config = GameConfig.parse(Json.parse("{\"record\": false, \"seats\": ["
                + "{\"type\": \"agent\", \"deck\": \"a\", \"time_bank_s\": " + bank + "},"
                + "{\"type\": \"agent\", \"deck\": \"b\"}]}"));
        return new GameSession("t" + UUID.randomUUID().toString().substring(0, 6), config, manager);
    }

    private static Decision decision(GameSession s, Seat seat) {
        Decision d = s.newDecision(Decision.YES_NO, UUID.randomUUID());
        d.addOption("yes", "Yes");
        d.responder = new Decision.Responder() {
            @Override
            public String validate(JsonObject action) {
                return null;
            }

            @Override
            public void apply(JsonObject action) {
            }
        };
        return d;
    }

    private static JsonObject answer(Decision d) {
        JsonObject a = Decision.choiceAction("yes");
        a.addProperty("decision_id", d.id);
        return a;
    }

    @Test
    public void instantAnswerWhilePublisherIsPausedChargesAlmostNothing() throws Exception {
        GameSession s = session(5);
        Seat seat = s.seat(0);
        Decision d = decision(s, seat);
        CountDownLatch exposed = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        AtomicReference<String> err = new AtomicReference<>("not run");
        // a client answers the instant the decision is visible, while the publisher is "paused" before it
        // installs the clock timer
        Thread client = new Thread(() -> {
            try {
                exposed.await();
                err.set(s.submit(seat, answer(d), null));
            } catch (InterruptedException ignored) {
            } finally {
                answered.countDown();
            }
        });
        client.start();
        s.expose(seat, d);
        exposed.countDown();
        assertTrue(answered.await(5, TimeUnit.SECONDS));
        assertNull(err.get());
        assertTrue("charged " + seat.clockUsedS, seat.clockUsedS >= 0 && seat.clockUsedS < 1.0);
        // the publisher resumes and installs its timer: expiry finds the decision answered and does nothing
        assertFalse(s.expireClock(seat, d));
        assertTrue(seat.clockUsedS < 1.0);
    }

    @Test
    public void expiryClaimsAPendingDecision() {
        GameSession s = session(5);
        Seat seat = s.seat(0);
        Decision d = decision(s, seat);
        s.expose(seat, d);
        assertTrue(s.expireClock(seat, d));
        assertNull(seat.pending.get());
        assertEquals(5.0, seat.clockUsedS, 1e-9);
        // a late answer now finds nothing to answer
        assertTrue(s.submit(seat, answer(d), null).contains("no decision pending"));
    }

    @Test
    public void chargesAccumulateAcrossDecisions() throws Exception {
        GameSession s = session(30);
        Seat seat = s.seat(0);
        for (int i = 0; i < 3; i++) {
            Decision d = decision(s, seat);
            s.expose(seat, d);
            Thread.sleep(50);
            assertNull(s.submit(seat, answer(d), null));
        }
        assertTrue("charged " + seat.clockUsedS, seat.clockUsedS >= 0.15 && seat.clockUsedS < 2.0);
    }

    @Test
    public void answerAfterTheBankRanOutStillUnblocksTheGame() throws Exception {
        GameSession s = session(0.05);
        Seat seat = s.seat(0);
        CountDownLatch applied = new CountDownLatch(1);
        Decision d = decision(s, seat);
        d.responder = new Decision.Responder() {
            @Override
            public String validate(JsonObject action) {
                return null;
            }

            @Override
            public void apply(JsonObject action) {
                applied.countDown();
            }
        };
        s.expose(seat, d);
        Thread.sleep(120); // the timer would have fired; the answer races it and loses on the clock
        assertNull(s.submit(seat, answer(d), null));
        assertEquals(0.05, seat.clockUsedS, 1e-9);
        assertNull(seat.pending.get());
        // the waiting game thread is released (the forfeit itself is processed by XMage)
        assertTrue(applied.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void expiryAnswersTheClaimedDialogWithItsDefault() throws Exception {
        GameSession s = session(5);
        Seat seat = s.seat(0);
        AtomicReference<JsonObject> applied = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Decision d = decision(s, seat);
        d.defaultAction = Decision.choiceAction("yes");
        d.responder = new Decision.Responder() {
            @Override
            public String validate(JsonObject action) {
                return null;
            }

            @Override
            public void apply(JsonObject action) {
                applied.set(action);
                done.countDown();
            }
        };
        s.expose(seat, d);
        assertTrue(s.expireClock(seat, d));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("yes", applied.get().get("choice").getAsString());
    }
}
