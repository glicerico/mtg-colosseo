package colosseo.engine;

import com.google.gson.JsonArray;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Registry of games hosted by this server.
 */
public final class GameManager {

    private static final int KEEP_FINISHED = 500;

    private final Map<String, GameSession> games = new LinkedHashMap<>();
    private final DeckLibrary decks;
    private final Path dataDir;
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "colosseo-timers");
        t.setDaemon(true);
        return t;
    });

    private final int maxRunning;
    private final double defaultAbandonTimeoutS;

    /**
     * Thrown when the server already runs its maximum number of games.
     */
    public static final class TooManyGamesException extends RuntimeException {
        TooManyGamesException(String message) {
            super(message);
        }
    }

    public GameManager(DeckLibrary decks, Path dataDir) {
        this(decks, dataDir, 0, 0);
    }

    /**
     * @param maxRunning             maximum number of concurrently running games (0 = unlimited)
     * @param defaultAbandonTimeoutS stop games whose bridge seat stays disconnected this long (0 = never);
     *                               games may override it with "abandon_timeout_s"
     */
    public GameManager(DeckLibrary decks, Path dataDir, int maxRunning, double defaultAbandonTimeoutS) {
        this.decks = decks;
        this.dataDir = dataDir;
        this.maxRunning = maxRunning;
        this.defaultAbandonTimeoutS = defaultAbandonTimeoutS;
        timers.scheduleWithFixedDelay(this::checkLiveness, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    public int maxRunning() {
        return maxRunning;
    }

    public synchronized int running() {
        int n = 0;
        for (GameSession g : games.values()) {
            if ("running".equals(g.status()) || "created".equals(g.status())) {
                n++;
            }
        }
        return n;
    }

    private void checkLiveness() {
        long now = System.currentTimeMillis();
        for (GameSession g : list()) {
            try {
                g.checkLiveness(now, defaultAbandonTimeoutS);
            } catch (RuntimeException e) {
                org.apache.log4j.Logger.getLogger(GameManager.class).warn("liveness check failed for " + g.id, e);
            }
        }
    }

    public DeckLibrary decks() {
        return decks;
    }

    public Path dataDir() {
        return dataDir;
    }

    ScheduledExecutorService timers() {
        return timers;
    }

    public GameSession create(GameConfig config) {
        // ids are listed publicly; they are not secrets (tokens are)
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        GameSession session = new GameSession(id, config, this);
        synchronized (this) {
            if (maxRunning > 0 && running() >= maxRunning) {
                throw new TooManyGamesException("the server already runs " + maxRunning + " games; try again later");
            }
            games.put(id, session);
        }
        try {
            session.start();
        } catch (RuntimeException e) {
            synchronized (this) {
                games.remove(id);
            }
            throw e;
        }
        return session;
    }

    public synchronized GameSession get(String id) {
        return games.get(id);
    }

    public synchronized List<GameSession> list() {
        return new ArrayList<>(games.values());
    }

    public JsonArray summaries() {
        JsonArray arr = new JsonArray();
        List<GameSession> all = list();
        for (int i = all.size() - 1; i >= 0; i--) {
            arr.add(all.get(i).summary());
        }
        return arr;
    }

    synchronized void onFinished(GameSession session) {
        // keep a bounded history of finished games
        int finished = 0;
        List<String> toRemove = new ArrayList<>();
        List<GameSession> all = new ArrayList<>(games.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            GameSession g = all.get(i);
            if (!"running".equals(g.status()) && !"created".equals(g.status())) {
                finished++;
                if (finished > KEEP_FINISHED) {
                    toRemove.add(g.id);
                }
            }
        }
        toRemove.forEach(games::remove);
    }
}
