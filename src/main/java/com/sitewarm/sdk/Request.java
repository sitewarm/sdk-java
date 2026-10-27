package com.sitewarm.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Arguments of one operation. Build with {@link #of()} and the {@code with*} methods. */
public record Request(Map<String, String> path, Map<String, String> query, Object body, String idempotencyKey) {
    public static Request of() {
        return new Request(Map.of(), Map.of(), null, null);
    }

    public Request withPath(String name, String value) {
        return new Request(plus(path, name, value), query, body, idempotencyKey);
    }

    /** A query parameter; null values are skipped. */
    public Request withQuery(String name, String value) {
        return value == null ? this : new Request(path, plus(query, name, value), body, idempotencyKey);
    }

    public Request withBody(Object body) {
        return new Request(path, query, body, idempotencyKey);
    }

    /** Overrides the random Idempotency-Key sent for operations that accept one. */
    public Request withIdempotencyKey(String key) {
        return new Request(path, query, body, key);
    }

    private static Map<String, String> plus(Map<String, String> m, String k, String v) {
        Map<String, String> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return Map.copyOf(out);
    }
}
