package colosseo.engine;

import com.google.gson.JsonObject;
import io.javalin.websocket.WsContext;
import org.apache.log4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A WebSocket client attached to a game, either controlling a seat or spectating.
 * <p>
 * Sends never block the caller (often the game thread): messages are queued per connection and written in
 * order by a sender thread. A client that stops reading is disconnected once its queue grows too long.
 */
public final class Connection {

    private static final Logger LOG = Logger.getLogger(Connection.class);
    private static final int MAX_QUEUED = 5000;
    private static final ExecutorService SENDERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "colosseo-ws-send");
        t.setDaemon(true);
        return t;
    });

    private final WsContext ctx;
    public final String id;
    /**
     * spectator option: show both hands (only granted to authorized spectators)
     */
    public volatile boolean revealAll;
    /**
     * whether this spectator may switch {@link #revealAll} on
     */
    public volatile boolean mayReveal;
    public volatile Seat seat;

    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile boolean closing;

    private record Close(int code, String reason) {
    }

    public Connection(WsContext ctx) {
        this.ctx = ctx;
        this.id = ctx.sessionId();
    }

    public boolean isOpen() {
        try {
            return !closing && ctx.session.isOpen();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void send(JsonObject message) {
        send(Json.GSON.toJson(message));
    }

    public void send(String text) {
        if (!isOpen()) {
            return;
        }
        if (queued.incrementAndGet() > MAX_QUEUED) {
            LOG.warn("client " + id + " is not reading its messages, disconnecting");
            queue.clear();
            close(1008, "client too slow");
            return;
        }
        enqueue(text);
    }

    public void sendError(String message, Long decisionId) {
        send(error(message, decisionId));
    }

    public void sendError(String message, Long decisionId, String code) {
        JsonObject o = error(message, decisionId);
        o.addProperty("code", code);
        send(o);
    }

    /**
     * Sends an error message and then closes the connection (in that order).
     */
    public void reject(String message, int code, String reason) {
        send(error(message, null));
        close(code, reason);
    }

    public void close(int code, String reason) {
        if (closing) {
            return;
        }
        closing = true;
        enqueue(new Close(code, reason));
    }

    private static JsonObject error(String message, Long decisionId) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("message", message);
        if (decisionId != null) {
            o.addProperty("decision_id", decisionId);
        }
        return o;
    }

    private void enqueue(Object item) {
        queue.add(item);
        if (draining.compareAndSet(false, true)) {
            SENDERS.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            Object item;
            while ((item = queue.poll()) != null) {
                try {
                    if (item instanceof Close) {
                        Close c = (Close) item;
                        queue.clear();
                        ctx.closeSession(c.code(), c.reason());
                    } else {
                        queued.decrementAndGet();
                        ctx.send((String) item);
                    }
                } catch (RuntimeException e) {
                    LOG.debug("send failed for " + id + ": " + e.getMessage());
                }
            }
            draining.set(false);
            // a message may have been queued between the last poll and resetting the flag
            if (queue.isEmpty() || !draining.compareAndSet(false, true)) {
                return;
            }
        }
    }
}
