package colosseo.engine;

import mage.cards.repository.CardScanner;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.PatternLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time XMage initialization: logging and the card database (./db/cards.h2 in the working directory).
 */
public final class XmageBootstrap {

    private static volatile boolean done;

    private XmageBootstrap() {
    }

    public static synchronized void init(boolean verbose) {
        if (done) {
            return;
        }
        LogManager.resetConfiguration();
        ConsoleAppender console = new ConsoleAppender(new PatternLayout("%d{HH:mm:ss} %-5p [%c{1}] %m%n"));
        LogManager.getRootLogger().addAppender(console);
        LogManager.getRootLogger().setLevel(verbose ? Level.INFO : Level.WARN);
        LogManager.getLogger("colosseo").setLevel(Level.INFO);
        // XMage logs a lot of noise at INFO/WARN level during normal games
        LogManager.getLogger("mage").setLevel(verbose ? Level.INFO : Level.ERROR);
        LogManager.getLogger("org.eclipse.jetty").setLevel(Level.WARN);
        LogManager.getLogger("io.javalin").setLevel(Level.WARN);

        long t0 = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        if (!errors.isEmpty()) {
            LogManager.getLogger("colosseo").warn("card scan reported " + errors.size() + " problems (first: " + errors.get(0) + ")");
        }
        LogManager.getLogger("colosseo").info("card database ready in " + (System.currentTimeMillis() - t0) + " ms");
        done = true;
    }
}
