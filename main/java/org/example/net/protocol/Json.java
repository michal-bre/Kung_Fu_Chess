package org.example.net.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader/writer.
 *
 * The project has no build tool (no Maven/Gradle - see lib/, a hand-managed
 * folder of jars) and this sandbox's network access is restricted to an
 * allowlist that does not include Maven Central, so a JSON library isn't
 * something that can just be "added as a dependency" the way it normally
 * would be. Rather than vendor a large third-party parser for a wire
 * protocol this project fully controls on both ends (server AND client),
 * this class implements exactly the JSON subset the protocol needs: objects,
 * arrays, strings, numbers, booleans, and null.
 *
 * The in-memory value model is intentionally the simplest thing that can
 * represent any JSON document: {@code null}, {@link Boolean}, {@link Double}
 * (every number, integer or not - JSON itself has no separate int type),
 * {@link String}, {@code List<Object>} (array), and
 * {@code Map<String, Object>} (object, backed by LinkedHashMap so a written
 * object's keys come back out in the order they were put in - purely for
 * human-readable wire traffic when debugging, not a protocol requirement).
 */
public final class Json {

    private Json() {
    }

    /** Serializes any value built from the model described in the class doc into a JSON string. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            writeNumber((Number) value, out);
        } else if (value instanceof Map) {
            writeObject((Map<String, Object>) value, out);
        } else if (value instanceof List) {
            writeArray((List<Object>) value, out);
        } else {
            throw new IllegalArgumentException("Cannot serialize a " + value.getClass().getName() + " to JSON");
        }
    }

    private static void writeObject(Map<String, Object> object, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(entry.getKey(), out);
            out.append(':');
            writeValue(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(List<Object> array, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object item : array) {
            if (!first) out.append(',');
            first = false;
            writeValue(item, out);
        }
        out.append(']');
    }

    private static void writeNumber(Number number, StringBuilder out) {
        double d = number.doubleValue();
        // Whole-valued doubles print as "5" rather than "5.0" - every numeric
        // field this protocol sends (scores, millisecond timestamps, pixel
        // coordinates rounded for display) reads more naturally that way,
        // and JSON itself doesn't distinguish int from float on the wire
        // regardless.
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            out.append((long) d);
        } else {
            out.append(d);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /** Parses a JSON document into the value model described in the class doc. */
    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected trailing content in JSON at index " + parser.pos);
        }
        return value;
    }

    /** Convenience for the common case: parse a JSON object and get it back typed as a Map, rather than the caller casting Object every time. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object at the top level, got: " + value);
        }
        return (Map<String, Object>) value;
    }

    /** A straightforward recursive-descent parser over the source string, tracking a single cursor position. */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        char peek() {
            if (atEnd()) throw new IllegalArgumentException("Unexpected end of JSON input");
            return src.charAt(pos);
        }

        void expect(char c) {
            if (atEnd() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at index " + pos + " in: " + src);
            }
            pos++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expectLiteral("true"); return Boolean.TRUE;
                case 'f': expectLiteral("false"); return Boolean.FALSE;
                case 'n': expectLiteral("null"); return null;
                default: return parseNumber();
            }
        }

        private void expectLiteral(String literal) {
            if (pos + literal.length() > src.length() || !src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected literal '" + literal + "' at index " + pos);
            }
            pos += literal.length();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                } else if (next == '}') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at index " + pos);
                }
            }
            return result;
        }

        private List<Object> parseArray() {
            List<Object> result = new java.util.ArrayList<>();
            expect('[');
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                } else if (next == ']') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at index " + pos);
                }
            }
            return result;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') break;
                if (c == '\\') {
                    char escaped = peek();
                    pos++;
                    switch (escaped) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Truncated \\u escape at index " + pos);
                            }
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown escape '\\" + escaped + "' at index " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Double parseNumber() {
            int start = pos;
            if (!atEnd() && (peek() == '-' || peek() == '+')) pos++;
            while (!atEnd() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E'
                    || peek() == '-' || peek() == '+')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Expected a number at index " + pos);
            }
            return Double.parseDouble(src.substring(start, pos));
        }
    }
}
