package colosseo.engine;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who may do what on this server.
 * <ul>
 *   <li><b>tokens</b> (the default): controlling a seat needs that seat's token; revealing hands and stopping a
 *   game need the game's owner token (returned to whoever created the game) or the server API key. Hidden
 *   information is only as safe as the default, so this holds on localhost too.</li>
 *   <li><b>open</b> (explicit {@code --auth open}, for debugging and trusted exhibitions): anyone who can reach
 *   the server may control bridge seats, watch with both hands revealed and stop games.</li>
 * </ul>
 * A game created with {@code "require_tokens": true} gets the token rules even on an open server (useful for
 * benchmarks, so that an agent can neither take over nor peek at the other seat).
 * <p>
 * Independently, an API key (when configured) is required to create games. Browsers are additionally held
 * to same-origin requests (plus configured origins), and an open server bound to loopback only answers
 * requests addressed to a loopback host name, which defeats DNS rebinding.
 */
public final class AccessPolicy {

    public enum Mode {
        OPEN, TOKENS;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "open":
                    return OPEN;
                case "tokens":
                    return TOKENS;
                default:
                    throw new IllegalArgumentException("auth mode must be 'open' or 'tokens': " + s);
            }
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public final Mode mode;
    private final String apiKey;
    public final boolean allowDeckPaths;
    private final Set<String> allowedOrigins;
    /**
     * Host header allow-list, or null for no restriction.
     */
    private final Set<String> allowedHosts;

    public AccessPolicy(Mode mode, String apiKey, boolean allowDeckPaths, Collection<String> allowedOrigins,
                        Collection<String> allowedHosts) {
        this.mode = mode;
        this.apiKey = apiKey == null || apiKey.isEmpty() ? null : apiKey;
        this.allowDeckPaths = allowDeckPaths;
        this.allowedOrigins = new LinkedHashSet<>();
        for (String o : allowedOrigins) {
            this.allowedOrigins.add(normalizeOrigin(o));
        }
        if (allowedHosts == null) {
            this.allowedHosts = null;
        } else {
            Set<String> hosts = new LinkedHashSet<>(LOOPBACK_NAMES);
            for (String h : allowedHosts) {
                hosts.add(h.toLowerCase(Locale.ROOT));
            }
            this.allowedHosts = hosts;
        }
    }

    /**
     * Policy for a server bound to {@code bindHost} with the given options; {@code mode}/{@code allowDeckPaths}
     * may be null for the defaults (tokens; deck file paths only on a loopback address).
     */
    public static AccessPolicy forBind(String bindHost, Mode mode, String apiKey, Boolean allowDeckPaths,
                                       Collection<String> allowedOrigins, Collection<String> extraHosts) {
        boolean loopback = isLoopback(bindHost);
        Mode m = mode != null ? mode : Mode.TOKENS;
        boolean paths = allowDeckPaths != null ? allowDeckPaths : loopback;
        // DNS rebinding only matters for an open server that relies on not being reachable from outside
        Collection<String> hosts = m == Mode.OPEN && loopback ? extraHosts : null;
        return new AccessPolicy(m, apiKey, paths, allowedOrigins, hosts);
    }

    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (LOOPBACK_NAMES.contains(h)) {
            return true;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // --- secrets ---------------------------------------------------------------------------------

    /**
     * A new unguessable token (128 bits, hex).
     */
    public static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /**
     * Constant-time comparison; null or empty never matches.
     */
    public static boolean matches(String expected, String given) {
        if (expected == null || given == null || expected.isEmpty() || given.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    public boolean apiKeyRequired() {
        return apiKey != null;
    }

    // --- decisions -------------------------------------------------------------------------------

    /**
     * Whether a game is protected by tokens (server in tokens mode or the game asked for it).
     */
    public boolean isProtected(GameConfig config) {
        return mode == Mode.TOKENS || config.requireTokens;
    }

    public boolean canCreate(String credential) {
        return apiKey == null || matches(apiKey, credential);
    }

    public boolean canControlSeat(GameConfig config, String seatToken, String credential) {
        return !isProtected(config) || matches(seatToken, credential);
    }

    /**
     * Revealing hands to a spectator and stopping the game.
     */
    public boolean canAdminister(GameConfig config, String ownerToken, String credential) {
        return !isProtected(config) || matches(ownerToken, credential) || matches(apiKey, credential);
    }

    // --- browser checks --------------------------------------------------------------------------

    /**
     * Host header check (an open loopback server only answers to loopback names).
     */
    public boolean hostAllowed(String hostHeader) {
        if (allowedHosts == null) {
            return true;
        }
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }
        return allowedHosts.contains(hostName(hostHeader).toLowerCase(Locale.ROOT));
    }

    /**
     * Origin check for WebSocket upgrades and state-changing requests. Non-browser clients send no Origin.
     */
    public boolean originAllowed(String origin, String hostHeader) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        String o = normalizeOrigin(origin);
        if (allowedOrigins.contains("*") || allowedOrigins.contains(o)) {
            return true;
        }
        if (hostHeader == null) {
            return false;
        }
        String h = hostHeader.toLowerCase(Locale.ROOT);
        return o.equals("http://" + h) || o.equals("https://" + h);
    }

    public Set<String> corsOrigins() {
        return allowedOrigins;
    }

    static String hostName(String hostHeader) {
        String h = hostHeader.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 && h.indexOf(':', colon + 1) < 0 ? h.substring(0, colon) : h;
    }

    static String normalizeOrigin(String origin) {
        String o = origin.trim().toLowerCase(Locale.ROOT);
        if (o.equals("*") || o.equals("null")) {
            return o;
        }
        try {
            URI u = URI.create(o);
            if (u.getScheme() == null || u.getHost() == null) {
                return o;
            }
            int port = u.getPort();
            boolean defaultPort = port == -1 || ("http".equals(u.getScheme()) && port == 80) || ("https".equals(u.getScheme()) && port == 443);
            return u.getScheme() + "://" + u.getHost() + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return o;
        }
    }
}
