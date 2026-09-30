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

    public GameManager(DeckLibrary decks, Path dataDir) {
        this.decks = decks;
        this.dataDir = dataDir;
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
        String id = UUID.randomUUID().toString().substring(0, 8);
        GameSession session = new GameSession(id, config, this);
        synchronized (this) {
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
