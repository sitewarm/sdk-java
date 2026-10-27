package com.sitewarm.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitewarm.sdk.model.Result;
import com.sitewarm.sdk.model.Site;
import com.sitewarm.sdk.model.SiteCreate;
import com.sitewarm.sdk.model.WarmRun;
import com.sitewarm.sdk.model.WarmRunCreate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the sitewarm API (pre-release). Thread-safe. Every operation of the spec is reachable by its
 * operationId through {@link #call}; {@link #sites} and {@link #warmRuns} are typed conveniences.
 */
public final class SiteWarm {
    private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7e]+$");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)}");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");

    /** Largest response body that is read (32 MiB). */
    static final int MAX_BODY = 32 << 20;
    static final int MAX_PAGES = 10_000;

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    /** Pauses between attempts; replaceable in tests. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** SDK version, sent in the User-Agent. A test keeps it equal to the pom version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    static final String DEFAULT_USER_AGENT = "sitewarm-java/" + VERSION + " (+https://github.com/sitewarm/sdk-java)";

    private static final Set<String> REPEATABLE = Set.of("GET", "HEAD", "PUT", "OPTIONS");

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final int maxRetries;
    private final Duration retryBase;
    private final Duration retryMax;
    private final Duration timeout;
    private final String userAgent;
    private final Sleeper sleeper;

    public final Sites sites = new Sites();
    public final WarmRuns warmRuns = new WarmRuns();

    private SiteWarm(Builder b) {
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(b.timeout).build();
        this.maxRetries = b.maxRetries;
        this.retryBase = b.retryBase;
        this.retryMax = b.retryMax;
        this.timeout = b.timeout;
        this.userAgent = b.userAgent;
        this.sleeper = b.sleeper;
    }

    public static Builder builder(String apiKey, String baseUrl) {
        return new Builder(apiKey, baseUrl);
    }

    /** Never contains the key. */
    @Override
    public String toString() {
        return "SiteWarm{baseUrl=" + baseUrl + "}";
    }

    public static final class Builder {
        private final String apiKey;
        private final String baseUrl;
        private int maxRetries = 3;
        private Duration retryBase = Duration.ofMillis(500);
        private Duration retryMax = Duration.ofSeconds(30);
        private Duration timeout = Duration.ofSeconds(30);
        private String userAgent = DEFAULT_USER_AGENT;
        private Sleeper sleeper = d -> Thread.sleep(d.toMillis());

        private Builder(String apiKey, String baseUrl) {
            // Messages below never echo the key.
            if (apiKey == null || !KEY.matcher(apiKey).matches()) {
                throw new SiteWarmException("apiKey must be non-empty printable ASCII without whitespace");
            }
            this.apiKey = apiKey;
            this.baseUrl = normalize(baseUrl);
        }

        /** Retries after the first attempt (default 3). A negative value keeps the default. */
        public Builder maxRetries(int n) { if (n >= 0) this.maxRetries = n; return this; }
        public Builder retryDelays(Duration base, Duration max) { this.retryBase = base; this.retryMax = max; return this; }
        /** Per-attempt timeout (default 30 s). Null, zero or a negative value keeps the default. */
        public Builder timeout(Duration d) { if (d != null && !d.isZero() && !d.isNegative()) this.timeout = d; return this; }
        /** Overrides the User-Agent. Null or empty keeps the default. */
        public Builder userAgent(String s) { if (s != null && !s.isEmpty()) this.userAgent = s; return this; }
        Builder sleeper(Sleeper s) { this.sleeper = s; return this; }

        public SiteWarm build() { return new SiteWarm(this); }
    }

    private static String normalize(String raw) {
        URI u;
        try {
            u = URI.create(raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw new SiteWarmException("baseUrl must be an absolute URL such as https://<host>/v1");
        }
        if (u.getScheme() == null || u.getHost() == null) {
            throw new SiteWarmException("baseUrl must be an absolute URL such as https://<host>/v1");
        }
        String host = u.getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!u.getScheme().equals("https") && !(u.getScheme().equals("http") && loopback)) {
            throw new SiteWarmException("baseUrl must use https (http is only allowed for localhost)");
        }
        if (u.getUserInfo() != null) throw new SiteWarmException("baseUrl must not contain credentials");
        if (u.getQuery() != null || u.getFragment() != null) {
            throw new SiteWarmException("baseUrl must not contain a query or fragment");
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
        return u.getScheme() + "://" + u.getRawAuthority() + path;
    }

    /** Calls one operation by operationId and returns the decoded JSON body (null for an empty body). */
    public JsonNode call(String operationId, Request req) {
        return exchange(operationId, req).body();
    }

    /** Like {@link #call(String, Request)} but maps the body to {@code type} (a generated model). */
    public <T> T call(String operationId, Request req, Class<T> type) {
        JsonNode n = call(operationId, req);
        if (n == null) return null;
        try {
            return MAPPER.treeToValue(n, type);
        } catch (IOException e) {
            throw new SiteWarmException("response does not match " + type.getSimpleName(), e);
        }
    }

    /** A decoded response with its transport data. */
    public record Response(int status, Map<String, java.util.List<String>> headers, JsonNode body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    public Response exchange(String operationId, Request req) {
        Operation op = Operations.TABLE.get(operationId);
        if (op == null) throw new SiteWarmException("unknown operation \"" + operationId + "\"");
        StringBuilder url = new StringBuilder(baseUrl).append(expand(op.path(), req.path()));
        Map<String, String> query = checkedQuery(req.query());
        if (!query.isEmpty()) {
            url.append('?');
            query.forEach((k, v) -> url.append(enc(k)).append('=').append(enc(v)).append('&'));
            url.setLength(url.length() - 1);
        }
        String body = null;
        if (req.body() != null) {
            try {
                body = MAPPER.writeValueAsString(req.body());
            } catch (IOException e) {
                throw new SiteWarmException("could not encode the request body", e);
            }
        }
        boolean sendsKey = op.idempotency() != Operation.Idempotency.NONE && op.method().equals("POST");
        // One key per call, reused by every retry, so a retried POST never starts a second run.
        String key = sendsKey ? (req.idempotencyKey() != null ? req.idempotencyKey() : UUID.randomUUID().toString()) : null;
        // 429 = not processed. 5xx, network errors and broken bodies may have been, so retry them only when
        // repeating is safe: GET, HEAD, PUT, OPTIONS, or a POST with an Idempotency-Key. DELETE and PATCH: 429 only.
        boolean retryAmbiguous = REPEATABLE.contains(op.method()) || sendsKey;

        for (int attempt = 0; ; attempt++) {
            boolean last = attempt >= maxRetries;
            HttpRequest request;
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json, application/problem+json")
                        .header("User-Agent", userAgent);
                if (body != null) rb.header("Content-Type", "application/json");
                if (key != null) rb.header("Idempotency-Key", key);
                rb.method(op.method(), body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                request = rb.build();
            } catch (IllegalArgumentException e) {
                // An invalid header value or URL: repeating cannot fix it. The message may echo a value, so it is dropped.
                throw new SiteWarmException("malformed request (invalid header or URL)");
            }

            HttpResponse<InputStream> resp;
            try {
                resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SiteWarmException("interrupted", e);
            } catch (IOException e) {
                if (!last && retryAmbiguous && isTransient(e)) {
                    pause(backoff(attempt));
                    continue;
                }
                boolean timedOut = e instanceof java.net.http.HttpTimeoutException;
                // The cause is dropped on purpose: transport errors can carry the request URL.
                throw new SiteWarmException.Connection(timedOut ? "request timed out" : "request failed", timedOut, null);
            }

            int status = resp.statusCode();
            String text;
            try (InputStream in = resp.body()) {
                text = new String(readLimited(in, MAX_BODY), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // A body that breaks off is as ambiguous as a network error, except after a 429.
                if (!last && (retryAmbiguous || status == 429)) {
                    pause(backoff(attempt));
                    continue;
                }
                boolean timedOut = e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.SocketTimeoutException;
                throw new SiteWarmException.Connection(timedOut ? "request timed out" : "response body failed", timedOut, null);
            }
            if (status >= 200 && status < 300) {
                return new Response(status, resp.headers().map(), parse(text, status));
            }
            Duration retryAfter = parseRetryAfter(resp.headers().firstValue("Retry-After").orElse(null));
            SiteWarmException.Api error = apiError(status, text, resp.headers(), retryAfter);
            boolean retryable = status == 429 || (status >= 500 && status != 501 && retryAmbiguous);
            // A 409 "conflict" on a keyed POST is the original request still running (a timeout retry racing it).
            // "idempotency-key-reused" is a caller bug and is never retried.
            if (status == 409 && sendsKey && "conflict".equals(error.code())) retryable = true;
            if (retryable && !last) {
                // Never a hot retry on "Retry-After: 0" or a date in the past; jitter spreads clients out.
                // A server-provided wait longer than the cap is surfaced, not slept.
                Duration floored = retryAfter == null ? null : retryAfter.compareTo(retryBase) < 0 ? retryBase : retryAfter;
                if (floored == null || floored.compareTo(retryMax) <= 0) {
                    pause(floored == null ? backoff(attempt)
                            : floored.plusMillis((long) (ThreadLocalRandom.current().nextDouble() * floored.toMillis() / 4)));
                    continue;
                }
            }
            throw error;
        }
    }

    /**
     * False for failures that repeating cannot fix: certificate verification, unknown host and malformed requests.
     * Other network errors are retried.
     */
    static boolean isTransient(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            // HttpClient reports an unresolvable host as a ConnectException caused by UnresolvedAddressException.
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof javax.net.ssl.SSLPeerUnverifiedException
                    || t instanceof java.security.cert.CertificateException
                    || t instanceof java.security.cert.CertPathValidatorException) {
                return false;
            }
            String m = t.getMessage();
            if (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("invalid header")) return false;
        }
        return true;
    }

    private void pause(Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SiteWarmException("interrupted", e);
        }
    }

    private Duration backoff(int attempt) {
        double ceil = Math.min(retryMax.toMillis(), retryBase.toMillis() * Math.pow(2, attempt));
        return Duration.ofMillis((long) (ceil / 2 + ThreadLocalRandom.current().nextDouble() * ceil / 2));
    }

    private static JsonNode parse(String text, int status) {
        if (status == 204 || text == null || text.isBlank()) return null;
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new SiteWarmException.Decode(status); // no cause: a parser message can quote the body
        }
    }

    /** Reads at most {@code limit + 1} bytes; a larger body throws {@link SiteWarmException.ResponseTooLarge}. */
    static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > limit) throw new SiteWarmException.ResponseTooLarge(limit);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** {@code limit} must be 1..100 (empty or 0 = server default, not sent); invalid values never reach the server. */
    private static Map<String, String> checkedQuery(Map<String, String> query) {
        String limit = query.get("limit");
        if (limit == null) return query;
        String v = limit.trim();
        Map<String, String> out = new java.util.LinkedHashMap<>(query);
        if (v.isEmpty() || v.equals("0")) {
            out.remove("limit");
            return out;
        }
        int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new SiteWarmException("limit must be an integer between 1 and 100");
        }
        if (n < 1 || n > 100) throw new SiteWarmException("limit must be an integer between 1 and 100");
        return out;
    }

    private static SiteWarmException.Api apiError(int status, String body, java.net.http.HttpHeaders headers, Duration retryAfter) {
        JsonNode p;
        try {
            p = MAPPER.readTree(body);
        } catch (IOException | RuntimeException e) {
            p = null;
        }
        if (p == null || !p.isObject()) {
            p = MAPPER.createObjectNode().put("type", "about:blank").put("title", "HTTP " + status).put("status", status);
        }
        String code = p.path("code").isTextual() ? p.get("code").asText() : null;
        if (code == null && p.path("type").isTextual()) {
            String t = p.get("type").asText().replaceAll("/+$", "");
            String slug = t.substring(t.lastIndexOf('/') + 1);
            if (SLUG.matcher(slug).matches()) code = slug;
        }
        OptionalInt remaining = OptionalInt.empty();
        String rl = headers.firstValue("RateLimit-Remaining").map(String::trim).orElse("");
        if (rl.matches("\\d+")) remaining = OptionalInt.of(Integer.parseInt(rl));
        return new SiteWarmException.Api(status, p, code, retryAfter, remaining);
    }

    static Duration parseRetryAfter(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (s.matches("\\d+")) return Duration.ofSeconds(Long.parseLong(s));
        try {
            Instant at = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration d = Duration.between(Instant.now(), at);
            return d.isNegative() ? Duration.ZERO : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String expand(String template, Map<String, String> values) {
        Matcher m = PATH_PARAM.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null || v.isEmpty()) throw new SiteWarmException("missing path parameter \"" + m.group(1) + "\"");
            m.appendReplacement(out, Matcher.quoteReplacement(enc(v)));
        }
        return m.appendTail(out).toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---- pagination ----

    /**
     * Iterates every item of a list operation across pages. Cursors are opaque. Throws if a cursor repeats or
     * if 10,000 pages did not reach the end.
     */
    public <T> Iterable<T> items(String operationId, Request req, Class<T> type) {
        return items(operationId, req, type, MAX_PAGES);
    }

    <T> Iterable<T> items(String operationId, Request req, Class<T> type, int maxPages) {
        return () -> new Iterator<>() {
            private String cursor = req.query().get("cursor");
            private final Set<String> seen = new HashSet<>(cursor == null ? Set.of() : Set.of(cursor));
            private Iterator<JsonNode> page = java.util.Collections.emptyIterator();
            private boolean more = true;
            private int pages = 0;

            @Override
            public boolean hasNext() {
                while (!page.hasNext() && more) {
                    if (pages >= maxPages) {
                        throw new SiteWarmException("pagination stopped after " + maxPages + " pages without reaching the end");
                    }
                    pages++;
                    Request r = cursor == null ? req : req.withQuery("cursor", cursor);
                    JsonNode n = call(operationId, r);
                    page = n.path("data").iterator();
                    JsonNode info = n.path("page");
                    String next = info.path("next_cursor").isTextual() ? info.get("next_cursor").asText() : null;
                    more = info.path("has_more").asBoolean(false) && next != null && !next.isEmpty();
                    if (more && !seen.add(next)) throw new SiteWarmException("pagination cursor did not advance");
                    cursor = next;
                }
                return page.hasNext();
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    return MAPPER.treeToValue(page.next(), type);
                } catch (IOException e) {
                    throw new SiteWarmException("response does not match " + type.getSimpleName(), e);
                }
            }
        };
    }

    // ---- typed conveniences ----

    public final class Sites {
        private Sites() {}

        public Site get(String siteId) {
            return call("getSite", Request.of().withPath("siteId", siteId), Site.class);
        }

        public Site create(SiteCreate body) {
            return call("createSite", Request.of().withBody(body), Site.class);
        }

        public Iterable<Site> all() {
            return items("listSites", Request.of(), Site.class);
        }
    }

    public final class WarmRuns {
        private WarmRuns() {}

        /** Starts a run (202). An Idempotency-Key is always sent (random unless {@code idempotencyKey} is given). */
        public WarmRun create(String siteId, WarmRunCreate body, String idempotencyKey) {
            return call("createWarmRun",
                    Request.of().withPath("siteId", siteId).withBody(body).withIdempotencyKey(idempotencyKey), WarmRun.class);
        }

        public WarmRun create(String siteId, WarmRunCreate body) {
            return create(siteId, body, null);
        }

        public WarmRun get(String runId) {
            return call("getWarmRun", Request.of().withPath("runId", runId), WarmRun.class);
        }

        public WarmRun cancel(String runId) {
            return call("cancelWarmRun", Request.of().withPath("runId", runId), WarmRun.class);
        }

        /** All results of a run; {@code finalState} (null for all) filters by final cache state. */
        public Iterable<Result> results(String runId, String finalState) {
            return items("listWarmRunResults",
                    Request.of().withPath("runId", runId).withQuery("final_state", finalState), Result.class);
        }

        /**
         * Polls until the run is completed, canceled or failed, honoring Retry-After. Throws
         * {@link SiteWarmException.WaitTimeout} on timeout and does NOT cancel the run.
         */
        public WarmRun waitFor(String runId, Duration timeout, Duration pollInterval) {
            Instant deadline = Instant.now().plus(timeout);
            Duration min = Duration.ofSeconds(1);
            Duration max = Duration.ofSeconds(60);
            while (true) {
                Response r = exchange("getWarmRun", Request.of().withPath("runId", runId));
                String status = r.body().path("status").asText();
                if (status.equals("completed") || status.equals("canceled") || status.equals("failed")) {
                    return convert(r.body());
                }
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) throw new SiteWarmException.WaitTimeout(runId, status);
                Duration hinted = parseRetryAfter(r.header("Retry-After"));
                Duration delay = hinted != null ? hinted : pollInterval;
                delay = delay.compareTo(min) < 0 ? min : delay.compareTo(max) > 0 ? max : delay;
                if (delay.compareTo(remaining) >= 0) {
                    pause(remaining);
                    WarmRun fin = get(runId);
                    if (isTerminal(fin)) return fin;
                    throw new SiteWarmException.WaitTimeout(runId, String.valueOf(fin.getStatus()));
                }
                pause(delay);
            }
        }

        private boolean isTerminal(WarmRun run) {
            String s = String.valueOf(run.getStatus());
            return s.equals("completed") || s.equals("canceled") || s.equals("failed");
        }

        private WarmRun convert(JsonNode n) {
            try {
                return MAPPER.treeToValue(n, WarmRun.class);
            } catch (IOException e) {
                throw new SiteWarmException("response does not match WarmRun", e);
            }
        }
    }
}
