package colosseo.engine;

import com.google.gson.JsonObject;
import io.javalin.websocket.WsContext;
import org.apache.log4j.Logger;

/**
 * A WebSocket client attached to a game, either controlling a seat or spectating.
 */
public final class Connection {

    private static final Logger LOG = Logger.getLogger(Connection.class);

    private final WsContext ctx;
    public final String id;
    /**
     * spectator option: show both hands
     */
    public volatile boolean revealAll;
    public volatile Seat seat;

    public Connection(WsContext ctx) {
        this.ctx = ctx;
        this.id = ctx.sessionId();
    }

    public boolean isOpen() {
        try {
            return ctx.session.isOpen();
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
        // Jetty does not allow concurrent blocking sends on one session
        synchronized (this) {
            try {
                ctx.send(text);
            } catch (RuntimeException e) {
                LOG.debug("send failed for " + id + ": " + e.getMessage());
            }
        }
    }

    public void sendError(String message, Long decisionId) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("message", message);
        if (decisionId != null) {
            o.addProperty("decision_id", decisionId);
        }
        send(o);
    }
}
