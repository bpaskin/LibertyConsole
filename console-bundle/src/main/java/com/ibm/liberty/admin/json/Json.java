package com.ibm.liberty.admin.json;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small JSON writer, parser and bean binder.
 * <p>
 * The console has its own JSON support so that enabling it doesn't add
 * jsonb/jsonp to the server or change the APIs its applications see. Output follows JSON-B's
 * defaults: properties in alphabetical order, null properties left out, and
 * java.time values written as ISO-8601 strings.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ write

    /** Serializes maps, collections, arrays, strings, numbers, booleans, java.time values and beans. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(out, value, 0);
        return out.toString();
    }

    /** Builds an insertion-ordered map from alternating keys and values. */
    public static Map<String, Object> object(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keysAndValues must contain key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (depth > 64) {
            throw new IllegalStateException("Object graph is too deep (cycle?)");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence || value instanceof Character) {
            writeString(out, value.toString());
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            out.append(Double.isFinite(d) ? value.toString() : "null");
        } else if (value instanceof Number) {
            out.append(value);
        } else if (value instanceof Enum<?>) {
            writeString(out, ((Enum<?>) value).name());
        } else if (value instanceof TemporalAccessor) {
            writeString(out, value.toString());
        } else if (value instanceof Date) {
            writeString(out, ((Date) value).toInstant().toString());
        } else if (value instanceof Map<?, ?>) {
            writeMap(out, (Map<?, ?>) value, depth);
        } else if (value instanceof Collection<?>) {
            writeArray(out, ((Collection<?>) value).toArray(), depth);
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object[] items = new Object[length];
            for (int i = 0; i < length; i++) {
                items[i] = Array.get(value, i);
            }
            writeArray(out, items, depth);
        } else {
            writeMap(out, beanProperties(value), depth);
        }
    }

    private static void writeMap(StringBuilder out, Map<?, ?> map, int depth) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue(), depth + 1);
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Object[] items, int depth) {
        out.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            writeValue(out, items[i], depth + 1);
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Also escape '<' and '>' so JSON can't close a <script> block, plus the JS line separators
                    if (c < 0x20 || c == '<' || c == '>' || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Public getter properties of a bean, alphabetically, with null values removed. */
    private static Map<String, Object> beanProperties(Object bean) {
        Map<String, Object> properties = new TreeMap<>();
        for (Method method : bean.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = method.getName();
            String property = null;
            if (name.startsWith("get") && name.length() > 3 && method.getReturnType() != void.class) {
                property = name.substring(3);
            } else if (name.startsWith("is") && name.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                property = name.substring(2);
            }
            if (property == null) {
                continue;
            }
            try {
                Object value = method.invoke(bean);
                if (value != null) {
                    properties.put(decapitalize(property), value);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to read property " + property + " of " + bean.getClass(), e);
            }
        }
        return properties;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name; // e.g. "URL" stays "URL", as in java.beans
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------ parse

    /**
     * Parses JSON text. Objects become {@code Map<String,Object>} (in document order),
     * arrays become {@code List<Object>}, numbers become Long or BigDecimal.
     *
     * @throws JsonException if the text is not valid JSON
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("No JSON content");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected content after the JSON value");
        }
        return value;
    }

    /** Parses JSON text that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Parses a JSON object into a new instance of {@code type} by calling its public
     * setters. Unknown properties are ignored, as JSON-B does by default.
     */
    public static <T> T read(String text, Class<T> type) {
        return bind(parseObject(text), type);
    }

    /** Binds an already-parsed JSON object to a new instance of {@code type}. */
    public static <T> T bind(Map<String, Object> json, Class<T> type) {
        T bean;
        try {
            bean = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonException("Cannot create " + type.getName() + ": " + e.getMessage());
        }
        for (Method method : type.getMethods()) {
            String name = method.getName();
            if (!name.startsWith("set") || name.length() <= 3 || method.getParameterCount() != 1
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String property = decapitalize(name.substring(3));
            if (!json.containsKey(property)) {
                continue;
            }
            Object converted = convert(json.get(property), method.getParameterTypes()[0],
                method.getGenericParameterTypes()[0], property);
            try {
                method.invoke(bean, converted);
            } catch (ReflectiveOperationException e) {
                throw new JsonException("Cannot set property " + property + ": " + e.getMessage());
            }
        }
        return bean;
    }

    private static Object convert(Object value, Class<?> target, Type genericTarget, String property) {
        if (value == null) {
            if (target.isPrimitive()) {
                return target == boolean.class ? Boolean.FALSE : defaultNumber(target);
            }
            return null;
        }
        if (target == String.class) {
            return value instanceof String ? value : write(value);
        }
        if (target == boolean.class || target == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            if (value instanceof String) {
                return Boolean.parseBoolean((String) value);
            }
            throw new JsonException("Property " + property + " must be a boolean");
        }
        if (Number.class.isAssignableFrom(wrap(target))) {
            BigDecimal number;
            try {
                number = value instanceof Number ? new BigDecimal(value.toString()) : new BigDecimal(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new JsonException("Property " + property + " must be a number");
            }
            Class<?> wrapped = wrap(target);
            if (wrapped == Integer.class) {
                return number.intValue();
            } else if (wrapped == Long.class) {
                return number.longValue();
            } else if (wrapped == Double.class) {
                return number.doubleValue();
            } else if (wrapped == Float.class) {
                return number.floatValue();
            } else if (wrapped == Short.class) {
                return number.shortValue();
            } else if (wrapped == Byte.class) {
                return number.byteValue();
            }
            return number;
        }
        if (List.class.isAssignableFrom(target) || Collection.class == target) {
            if (!(value instanceof List)) {
                throw new JsonException("Property " + property + " must be an array");
            }
            Class<?> elementType = Object.class;
            if (genericTarget instanceof ParameterizedType) {
                Type arg = ((ParameterizedType) genericTarget).getActualTypeArguments()[0];
                if (arg instanceof Class) {
                    elementType = (Class<?>) arg;
                }
            }
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(elementType == Object.class ? item : convert(item, elementType, elementType, property));
            }
            return result;
        }
        if (Map.class.isAssignableFrom(target)) {
            if (!(value instanceof Map)) {
                throw new JsonException("Property " + property + " must be an object");
            }
            return value;
        }
        if (target.isEnum()) {
            for (Object constant : target.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(value.toString())) {
                    return constant;
                }
            }
            throw new JsonException("Property " + property + " has an unknown value: " + value);
        }
        if (target == Object.class) {
            return value;
        }
        if (TemporalAccessor.class.isAssignableFrom(target) && value instanceof String) {
            // java.time types (LocalDateTime, Instant, ...) all have a static parse(CharSequence)
            try {
                return target.getMethod("parse", CharSequence.class).invoke(null, value);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new JsonException("Property " + property + " is not a valid " + target.getSimpleName());
            }
        }
        throw new JsonException("Property " + property + " of type " + target.getSimpleName() + " is not supported");
    }

    private static Object defaultNumber(Class<?> primitive) {
        if (primitive == int.class) {
            return 0;
        } else if (primitive == long.class) {
            return 0L;
        } else if (primitive == double.class) {
            return 0d;
        } else if (primitive == float.class) {
            return 0f;
        } else if (primitive == short.class) {
            return (short) 0;
        } else if (primitive == byte.class) {
            return (byte) 0;
        }
        return '\0';
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "boolean" -> Boolean.class;
            default -> Character.class;
        };
    }

    /** Thrown for malformed JSON or JSON that does not fit the target type. */
    public static final class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        JsonException error(String message) {
            return new JsonException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > 256) {
                throw error("JSON is nested too deeply");
            }
            if (atEnd()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> readObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("Expected a property name");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray(int depth) {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("Unterminated escape sequence");
                    }
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("Invalid unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Invalid unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("Control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            boolean decimal = false;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            try {
                if (!decimal) {
                    try {
                        return Long.parseLong(number);
                    } catch (NumberFormatException tooBig) {
                        return new BigDecimal(number);
                    }
                }
                return new BigDecimal(number);
            } catch (NumberFormatException e) {
                throw error("Invalid number '" + number + "'");
            }
        }

        private void expectWord(String word) {
            if (!text.startsWith(word, pos)) {
                throw error("Expected '" + word + "'");
            }
            pos += word.length();
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }
    }
}
