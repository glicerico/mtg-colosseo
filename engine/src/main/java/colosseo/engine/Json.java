package colosseo.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Small helpers around Gson's tree model.
 */
public final class Json {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>|</p>|</div>");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    private Json() {
    }

    public static JsonObject parse(String text) {
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return el.getAsJsonObject();
    }

    public static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    public static JsonArray ids(Collection<UUID> ids) {
        JsonArray arr = new JsonArray();
        if (ids != null) {
            for (UUID id : ids) {
                if (id != null) {
                    arr.add(id.toString());
                }
            }
        }
        return arr;
    }

    public static JsonArray strings(Collection<String> values) {
        JsonArray arr = new JsonArray();
        if (values != null) {
            for (String v : values) {
                arr.add(v);
            }
        }
        return arr;
    }

    public static String getString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    public static int getInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static long getLong(JsonObject obj, String key, long def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static boolean getBool(JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static JsonPrimitive prim(String s) {
        return new JsonPrimitive(s);
    }

    /**
     * XMage messages are HTML fragments (font colors, line breaks, links). Agents get plain text.
     */
    public static String plain(String html) {
        if (html == null) {
            return "";
        }
        String s = BREAK_TAG.matcher(html).replaceAll("\n");
        s = HTML_TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ")
                .replace("&mdash;", "\u2014")
                .replace("&ndash;", "\u2013")
                .replace("&bull;", "\u2022")
                .replace("&bull", "\u2022")
                .replace("&rsquo;", "'")
                .replace("&lsquo;", "'")
                .replace("&minus;", "-")
                .replace("&times;", "x")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(t);
            }
        }
        return out.toString();
    }
}
