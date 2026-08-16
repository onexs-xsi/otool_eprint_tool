package com.otool.eprint.scan;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline, side-effect-free parser for common QR text, URI query and key/value payloads. */
public final class QrContentParser {
    public static final class Parsed {
        public final String type;
        public final String raw;
        public final Map<String, String> fields;

        Parsed(String type, String raw, Map<String, String> fields) {
            this.type = type;
            this.raw = raw;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public String summary() {
            StringBuilder result = new StringBuilder("类型：").append(type)
                    .append("\n字符数：").append(raw.length());
            if (!fields.isEmpty()) result.append("\n字段数：").append(fields.size());
            for (Map.Entry<String, String> field : fields.entrySet()) {
                result.append("\n").append(field.getKey()).append(" = ")
                        .append(shortValue(field.getValue()));
            }
            return result.toString();
        }
    }

    private QrContentParser() {}

    public static Parsed parse(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return new Parsed("空内容", raw, Collections.emptyMap());
        Map<String, String> uriFields = parseUriQuery(raw);
        if (!uriFields.isEmpty()) return new Parsed("网址 / URI 参数", raw, uriFields);
        Map<String, String> structured = parseKeyValues(raw);
        if (!structured.isEmpty()) return new Parsed("结构化字段", raw, structured);
        return new Parsed("普通文本", raw, Collections.emptyMap());
    }

    private static Map<String, String> parseUriQuery(String raw) {
        int question = raw.indexOf('?');
        if (question <= 0 || question == raw.length() - 1 || raw.indexOf(':') <= 0) {
            return Collections.emptyMap();
        }
        String query = raw.substring(question + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) query = query.substring(0, fragment);
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (String item : query.split("&")) {
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String key = decode(item.substring(0, equals));
            String value = decode(item.substring(equals + 1));
            if (!key.isEmpty()) fields.put(key, value);
        }
        return fields;
    }

    private static Map<String, String> parseKeyValues(String raw) {
        String body = raw;
        if ((body.startsWith("{") && body.endsWith("}"))
                || (body.startsWith("[") && body.endsWith("]"))) {
            body = body.substring(1, body.length() - 1).trim();
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (String item : splitTopLevel(body)) {
            int separator = findSeparator(item);
            if (separator <= 0) continue;
            String key = unquote(item.substring(0, separator).trim());
            String value = unquote(item.substring(separator + 1).trim());
            if (isKey(key)) fields.put(key, value);
        }
        return fields.size() >= 2 || (raw.startsWith("{") && fields.size() == 1)
                ? fields : Collections.emptyMap();
    }

    private static List<String> splitTopLevel(String value) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && quote != 0) {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (current == quote) quote = 0;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '{' || current == '[' || current == '(') {
                depth++;
            } else if (current == '}' || current == ']' || current == ')') {
                depth = Math.max(0, depth - 1);
            } else if ((current == ',' || current == ';' || current == '\n') && depth == 0) {
                result.add(value.substring(start, index).trim());
                start = index + 1;
            }
        }
        result.add(value.substring(start).trim());
        return result;
    }

    private static int findSeparator(String item) {
        int colon = item.indexOf(':');
        int equals = item.indexOf('=');
        if (colon < 0) return equals;
        if (equals < 0) return colon;
        return Math.min(colon, equals);
    }

    private static boolean isKey(String value) {
        if (value.isEmpty() || value.length() > 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current) && current != '_' && current != '-'
                    && current != '.' && current != ' ') return false;
        }
        return true;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String shortValue(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }
}
