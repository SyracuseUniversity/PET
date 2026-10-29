package com.atakmap.android.lingualinkledger.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, in plain Java so the code using it is
 * tested on the PC; Android's org.json exists only as a stub in unit
 * tests. Objects become LinkedHashMap (keys in file order), arrays
 * ArrayList, whole numbers Long and other numbers Double, true/false
 * Boolean, null null.
 *
 * Records hold typed text (package name, description, author), so the
 * writer escapes whatever could break a file: quotes, backslashes, every
 * control character, and a lone surrogate (half of a pair), which UTF-8
 * cannot carry and would otherwise turn into "?". Other text is written
 * as it is, for UTF-8 files a person can read. Whatever string goes in
 * comes back out unchanged (JsonTest).
 */
final class Json {

    /**
     * One JSON value: a Map with String keys (written in its own order, so
     * use LinkedHashMap), a List, a String, a Number, a Boolean or null.
     * A number must be finite; JSON has no NaN or infinity.
     */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d))
                throw new IllegalArgumentException("JSON cannot hold " + d);
            sb.append(Double.toString(d)); // locale-independent, and reads back exactly
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!(e.getKey() instanceof String))
                    throw new IllegalArgumentException("JSON keys must be strings");
                if (!first)
                    sb.append(',');
                first = false;
                writeString(sb, (String) e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first)
                    sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    boolean lone = Character.isHighSurrogate(c)
                            ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                            : Character.isLowSurrogate(c)
                                    && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
                    // control characters, DEL, the line and paragraph separators, a lone surrogate
                    if (c < 0x20 || c == 0x7F || c == '\u2028' || c == '\u2029' || lone)
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        sb.append('"');
    }

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipSpace();
        Object value = p.value();
        p.skipSpace();
        if (p.pos != text.length())
            throw p.error("unexpected text after the value");
        return value;
    }

    private Object value() {
        if (pos >= text.length())
            throw error("unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9'))
                    return number();
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"')
                throw error("expected a key");
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            if (map.containsKey(key))
                throw error("duplicate key \"" + key + "\"");
            map.put(key, value());
            skipSpace();
            char c = next();
            if (c == '}')
                return map;
            if (c != ',')
                throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value());
            skipSpace();
            char c = next();
            if (c == ']')
                return list;
            if (c != ',')
                throw error("expected ',' or ']'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"')
                return sb.toString();
            if (c < 0x20)
                throw error("control character in a string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length())
                        throw error("short \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("bad escape \\" + e);
            }
        }
    }

    /** A whole number that fits is a Long, so it is written back as it was read; else a Double. */
    private Number number() {
        int start = pos;
        if (peek() == '-')
            pos++;
        while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0)
            pos++;
        String s = text.substring(start, pos);
        try {
            if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                try {
                    return Long.valueOf(s);
                } catch (NumberFormatException tooBig) {
                    // falls through to a Double
                }
            }
            return Double.valueOf(s);
        } catch (NumberFormatException ex) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos))
            throw error("expected " + word);
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0)
            pos++;
    }

    private char peek() {
        if (pos >= text.length())
            throw error("unexpected end");
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c)
            throw error("expected '" + c + "'");
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at character " + pos);
    }
}
