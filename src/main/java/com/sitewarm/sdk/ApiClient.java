package com.sitewarm.sdk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.StringJoiner;

/**
 * Static helpers that the generated model classes call ({@code toUrlQueryString}). Not an HTTP client: the
 * client is {@link SiteWarm}.
 */
public final class ApiClient {
    private ApiClient() {}

    public static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String valueToString(Object value) {
        if (value == null) return "";
        if (value instanceof java.time.OffsetDateTime t) return t.toString();
        return String.valueOf(value);
    }

    public static String collectionToString(Collection<?> values) {
        StringJoiner j = new StringJoiner(",");
        for (Object v : values) j.add(valueToString(v));
        return j.toString();
    }
}
