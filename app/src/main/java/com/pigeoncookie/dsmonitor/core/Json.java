package com.pigeoncookie.dsmonitor.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer.
 *
 * <p>org.json is available on Android but not on a desktop JVM, and this app is verified with
 * plain-JVM tests, so the parser is bundled instead. Only the subset the DeepSeek endpoints use
 * is supported: objects, arrays, strings, numbers, booleans and null.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        Json p = new Json(text);
        p.skipWs();
        if (p.pos >= p.src.length()) {
            return null;
        }
        return p.readValue();
    }

    // ------------------------------------------------------------------ reading

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) {
            return null;
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipWs();
            String key = readString();
            skipWs();
            expect(":");
            map.put(key, readValue());
            skipWs();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            pos++;
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON: expected , or } at " + pos);
            }
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            list.add(readValue());
            skipWs();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            pos++;
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON: expected , or ] at " + pos);
            }
        }
        return list;
    }

    private String readString() {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != '"') {
            throw new IllegalArgumentException("JSON: expected string at " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                break;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 <= src.length()) {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    break;
                default:
                    sb.append(e);
                    break;
            }
        }
        return sb.toString();
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        String token = src.substring(start, pos);
        if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException ignored) {
                // fall through to double
            }
        }
        try {
            return Double.valueOf(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JSON: bad number '" + token + "'");
        }
    }

    private void expect(String token) {
        if (!src.startsWith(token, pos)) {
            throw new IllegalArgumentException("JSON: expected " + token + " at " + pos);
        }
        pos += token.length();
    }

    // ------------------------------------------------------------------ access

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** Resolves a dotted path such as {@code data.biz_data.total.0.usage}. */
    public static Object at(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return root;
        }
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            Map<String, Object> map = asMap(cur);
            if (map != null) {
                cur = map.get(part);
                continue;
            }
            List<Object> list = asList(cur);
            if (list != null) {
                try {
                    int idx = Integer.parseInt(part);
                    cur = idx >= 0 && idx < list.size() ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
                continue;
            }
            return null;
        }
        return cur;
    }

    public static double num(Object v, double def) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                String s = ((String) v).trim();
                return s.isEmpty() ? def : Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        return def;
    }

    public static long lng(Object v, long def) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                String s = ((String) v).trim();
                return s.isEmpty() ? def : (long) Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static String str(Object v, String def) {
        if (v == null) {
            return def;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return def;
    }

    public static boolean bool(Object v, boolean def) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v);
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0;
        }
        return def;
    }

    public static double numAt(Object root, String path, double def) {
        return num(at(root, path), def);
    }

    public static long lngAt(Object root, String path, long def) {
        return lng(at(root, path), def);
    }

    public static String strAt(Object root, String path, String def) {
        return str(at(root, path), def);
    }

    public static boolean boolAt(Object root, String path, boolean def) {
        return bool(at(root, path), def);
    }

    /** Returns the first element when the value is an array, or the map itself. */
    public static Map<String, Object> firstMap(Object o) {
        Map<String, Object> map = asMap(o);
        if (map != null) {
            return map;
        }
        List<Object> list = asList(o);
        if (list != null && !list.isEmpty()) {
            return asMap(list.get(0));
        }
        return null;
    }

    // ------------------------------------------------------------------ writing

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
