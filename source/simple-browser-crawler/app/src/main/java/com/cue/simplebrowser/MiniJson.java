package com.cue.simplebrowser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal strict JSON reader for browser metadata, packaged resources, and user-imported extension manifests. */
final class MiniJson {
    private static final int MAX_NESTING_DEPTH = 128;
    private final String input;
    private int position;
    private int depth;

    private MiniJson(String input) {
        this.input = input;
    }

    static Object parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("JSON input is required");
        }
        MiniJson parser = new MiniJson(input);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != input.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String description) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, String description) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(description + " must be a JSON array");
        }
        return (List<Object>) value;
    }

    static String string(Object value, String description) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(description + " must be a non-empty string");
        }
        return (String) value;
    }

    static int integer(Object value, String description) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(description + " must be an integer");
        }
        Number number = (Number) value;
        double asDouble = number.doubleValue();
        int asInt = number.intValue();
        if (asDouble != asInt) {
            throw new IllegalArgumentException(description + " must be an integer");
        }
        return asInt;
    }

    private Object readValue() {
        skipWhitespace();
        if (position >= input.length()) {
            throw error("Expected a value");
        }
        char current = input.charAt(position);
        if (current == '{') {
            return readNestedObject();
        }
        if (current == '[') {
            return readNestedArray();
        }
        if (current == '"') {
            return readString();
        }
        if (current == 't') {
            readLiteral("true");
            return Boolean.TRUE;
        }
        if (current == 'f') {
            readLiteral("false");
            return Boolean.FALSE;
        }
        if (current == 'n') {
            readLiteral("null");
            return null;
        }
        if (current == '-' || (current >= '0' && current <= '9')) {
            return readNumber();
        }
        throw error("Unexpected character");
    }

    private Object readNestedObject() {
        enterContainer();
        try { return readObject(); } finally { depth--; }
    }

    private Object readNestedArray() {
        enterContainer();
        try { return readArray(); } finally { depth--; }
    }

    private void enterContainer() {
        if (depth >= MAX_NESTING_DEPTH) throw error("Maximum JSON nesting depth exceeded");
        depth++;
    }

    private Map<String, Object> readObject() {
        position++;
        skipWhitespace();
        Map<String, Object> result = new LinkedHashMap<>();
        if (consume('}')) {
            return result;
        }
        while (true) {
            skipWhitespace();
            if (position >= input.length() || input.charAt(position) != '"') {
                throw error("Expected an object key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            if (result.containsKey(key)) {
                throw error("Duplicate object key: " + key);
            }
            result.put(key, value);
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        position++;
        skipWhitespace();
        List<Object> result = new ArrayList<>();
        if (consume(']')) {
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (position < input.length()) {
            char current = input.charAt(position++);
            if (current == '"') {
                return value.toString();
            }
            if (current == '\\') {
                if (position >= input.length()) {
                    throw error("Unterminated escape sequence");
                }
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(readUnicodeEscape()); break;
                    default: throw error("Invalid escape sequence");
                }
            } else {
                if (current < 0x20) {
                    throw error("Unescaped control character");
                }
                value.append(current);
            }
        }
        throw error("Unterminated string");
    }

    private char readUnicodeEscape() {
        if (position + 4 > input.length()) {
            throw error("Incomplete Unicode escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(input.charAt(position++), 16);
            if (digit < 0) {
                throw error("Invalid Unicode escape");
            }
            code = (code << 4) | digit;
        }
        return (char) code;
    }

    private Number readNumber() {
        int start = position;
        consume('-');
        if (consume('0')) {
            if (position < input.length() && Character.isDigit(input.charAt(position))) {
                throw error("Leading zero in number");
            }
        } else {
            readDigits();
        }
        if (consume('.')) {
            readDigits();
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            readDigits();
        }
        String token = input.substring(start, position);
        try {
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException exception) {
            throw error("Invalid number");
        }
    }

    private void readDigits() {
        int start = position;
        while (position < input.length() && Character.isDigit(input.charAt(position))) {
            position++;
        }
        if (start == position) {
            throw error("Expected a digit");
        }
    }

    private void readLiteral(String literal) {
        if (!input.regionMatches(position, literal, 0, literal.length())) {
            throw error("Invalid literal");
        }
        position += literal.length();
    }

    private void skipWhitespace() {
        while (position < input.length()) {
            char current = input.charAt(position);
            if (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                position++;
            } else {
                return;
            }
        }
    }

    private boolean consume(char expected) {
        if (position < input.length() && input.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("Expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at character " + position);
    }
}
