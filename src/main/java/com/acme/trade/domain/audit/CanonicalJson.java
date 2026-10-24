package com.acme.trade.domain.audit;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic JSON for audit payloads: sorted keys and one specified escaping strategy. */
public final class CanonicalJson {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() {
    }

    public static String of(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("audit payload keys and values cannot be null");
            }
        }
        TreeMap<String, String> sorted = new TreeMap<>(values);
        StringBuilder json = new StringBuilder(Math.max(16, sorted.size() * 32));
        json.append('{');
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("audit payload keys and values cannot be null");
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            appendString(json, entry.getKey());
            json.append(':');
            appendString(json, entry.getValue());
        }
        return json.append('}').toString();
    }

    private static void appendString(StringBuilder target, String value) {
        target.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (c < 0x20) {
                        target.append("\\u00").append(HEX[(c >>> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        target.append(c);
                    }
                }
            }
        }
        target.append('"');
    }
}
