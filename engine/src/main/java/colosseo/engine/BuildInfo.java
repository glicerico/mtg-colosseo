package colosseo.engine;

import com.google.gson.JsonObject;

import java.io.InputStream;
import java.util.Properties;

/**
 * Versions this engine was built from (recorded in every game record so results can be traced to code).
 */
public final class BuildInfo {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/colosseo-build.properties")) {
            if (in != null) {
                PROPS.load(in);
            }
        } catch (Exception ignored) {
            // unknown versions are reported as such
        }
    }

    private BuildInfo() {
    }

    public static String get(String key) {
        String v = PROPS.getProperty(key);
        return v == null || v.startsWith("${") ? "unknown" : v;
    }

    public static JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("colosseo", get("colosseo.version"));
        o.addProperty("source_commit", get("source.commit"));
        o.addProperty("xmage", get("xmage.version"));
        o.addProperty("xmage_ref", get("xmage.ref"));
        return o;
    }
}
