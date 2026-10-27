package com.sitewarm.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitewarm.sdk.model.WarmRunCreate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SiteWarmTest {
    static final String KEY = "swk_secret_key";

    interface Handler {
        void handle(HttpExchange ex, int n) throws IOException;
    }

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final List<HttpExchange> seen = new CopyOnWriteArrayList<>();
    final List<String> idemKeys = new CopyOnWriteArrayList<>();

    SiteWarm client(Handler h, Consumer<SiteWarm.Builder> cfg) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(ex);
            idemKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            h.handle(ex, calls.incrementAndGet());
            ex.close();
        });
        server.start();
        SiteWarm.Builder b = SiteWarm.builder(KEY, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .retryDelays(Duration.ZERO, Duration.ZERO).sleeper(d -> { });
        cfg.accept(b);
        return b.build();
    }

    SiteWarm client(Handler h) throws IOException {
        return client(h, b -> { });
    }

    static void reply(HttpExchange ex, int status, String body, String... headers) throws IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) ex.getResponseHeaders().add(headers[i], headers[i + 1]);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void requestShapeAndAuth() throws Exception {
        SiteWarm c = client((ex, n) -> reply(ex, 200, "{\"id\":\"s1\"}"));
        JsonNode r = c.call("getSite", Request.of().withPath("siteId", "a/b"));
        assertEquals("s1", r.get("id").asText());
        assertEquals("/v1/sites/a%2Fb", seen.get(0).getRequestURI().getRawPath());
        assertEquals("Bearer " + KEY, seen.get(0).getRequestHeaders().getFirst("Authorization"));
    }

    @Test
    void problemBecomesTypedErrorWithoutKey() throws Exception {
        Object[][] cases = {{401, SiteWarmException.Api.Kind.AUTHENTICATION}, {403, SiteWarmException.Api.Kind.PERMISSION},
                {404, SiteWarmException.Api.Kind.NOT_FOUND}, {409, SiteWarmException.Api.Kind.CONFLICT},
                {422, SiteWarmException.Api.Kind.VALIDATION}, {429, SiteWarmException.Api.Kind.RATE_LIMITED},
                {500, SiteWarmException.Api.Kind.SERVER}};
        for (Object[] cs : cases) {
            int status = (Integer) cs[0];
            SiteWarm c = client((ex, n) -> reply(ex, status,
                    "{\"type\":\"https://x.test/problems/some-slug\",\"title\":\"T\",\"detail\":\"D\"}",
                    "Retry-After", "0", "RateLimit-Remaining", "0"), b -> b.maxRetries(0));
            var e = assertThrows(SiteWarmException.Api.class, () -> c.call("getSite", Request.of().withPath("siteId", "s1")));
            assertEquals(cs[1], e.kind());
            assertEquals(status, e.status());
            assertEquals("some-slug", e.code());
            assertEquals(0, e.rateLimitRemaining().getAsInt());
            assertFalse(e.getMessage().contains(KEY));
            stop();
        }
    }

    @Test
    void retries429ThenSucceeds() throws Exception {
        SiteWarm c = client((ex, n) -> {
            if (n < 3) reply(ex, 429, "{}", "Retry-After", "0");
            else reply(ex, 200, "{\"id\":\"s1\"}");
        });
        c.call("getSite", Request.of().withPath("siteId", "s1"));
        assertEquals(3, calls.get());
    }

    @Test
    void retryAfterBeyondCapIsSurfaced() throws Exception {
        SiteWarm c = client((ex, n) -> reply(ex, 429, "{}", "Retry-After", "120"),
                b -> b.retryDelays(Duration.ZERO, Duration.ofSeconds(5)));
        var e = assertThrows(SiteWarmException.Api.class, () -> c.call("getSite", Request.of().withPath("siteId", "s1")));
        assertEquals(1, calls.get());
        assertEquals(Duration.ofSeconds(120), e.retryAfter());
    }

    @Test
    void keyedPostIsRetriedWithOneKey() throws Exception {
        SiteWarm c = client((ex, n) -> reply(ex, 503, "{}"), b -> b.maxRetries(2));
        assertThrows(SiteWarmException.Api.class, () -> c.warmRuns.create("s1", new WarmRunCreate()));
        assertEquals(3, calls.get());
        assertEquals(1, new java.util.HashSet<>(idemKeys).size());
        assertNotEquals("null", idemKeys.get(0));
        java.util.UUID.fromString(idemKeys.get(0));
    }

    @Test
    void givenIdempotencyKeyIsSent() throws Exception {
        SiteWarm c = client((ex, n) -> reply(ex, 202, "{\"id\":\"run1\"}"));
        c.call("createWarmRun", Request.of().withPath("siteId", "s1").withBody(new WarmRunCreate()).withIdempotencyKey("mine"));
        assertEquals("mine", idemKeys.get(0));
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        SiteWarm c = client((ex, n) -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            ex.sendResponseHeaders(302, -1);
        }, b -> b.maxRetries(0));
        var e = assertThrows(SiteWarmException.Api.class, () -> c.call("getSite", Request.of().withPath("siteId", "s1")));
        assertEquals(302, e.status());
    }

    @Test
    void connectionErrorHasNoKeyOrCause() {
        SiteWarm c = SiteWarm.builder(KEY, "http://127.0.0.1:1/v1").maxRetries(0).build();
        var e = assertThrows(SiteWarmException.Connection.class, () -> c.call("getSite", Request.of().withPath("siteId", "s1")));
        assertFalse(e.getMessage().contains(KEY));
        assertNull(e.getCause());
    }

    @Test
    void paginationFollowsCursorAndDetectsStall() throws Exception {
        SiteWarm c = client((ex, n) -> {
            if (ex.getRequestURI().getQuery() == null) {
                reply(ex, 200, "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"page\":{\"has_more\":true,\"next_cursor\":\"c1\"}}");
            } else {
                reply(ex, 200, "{\"data\":[{\"id\":\"c\"}],\"page\":{\"has_more\":false,\"next_cursor\":null}}");
            }
        });
        List<String> ids = new ArrayList<>();
        for (var s : c.sites.all()) ids.add(s.getId());
        assertEquals(List.of("a", "b", "c"), ids);
        stop();

        SiteWarm stuck = client((ex, n) -> reply(ex, 200,
                "{\"data\":[{\"id\":\"a\"}],\"page\":{\"has_more\":true,\"next_cursor\":\"c1\"}}"));
        var ex = assertThrows(SiteWarmException.class, () -> stuck.sites.all().forEach(s -> { }));
        assertTrue(ex.getMessage().contains("did not advance"));
    }

    @Test
    void waitPollsUntilTerminalAndTimesOutWithoutCancel() throws Exception {
        String[] statuses = {"queued", "running", "completed"};
        SiteWarm c = client((ex, n) -> reply(ex, 200,
                "{\"id\":\"r1\",\"site_id\":\"s\",\"status\":\"" + statuses[Math.min(n, 3) - 1] + "\"}", "Retry-After", "0"));
        var run = c.warmRuns.waitFor("r1", Duration.ofMinutes(1), Duration.ofSeconds(1));
        assertEquals("completed", String.valueOf(run.getStatus()));
        stop();

        List<String> methods = new CopyOnWriteArrayList<>();
        SiteWarm never = client((ex, n) -> {
            methods.add(ex.getRequestMethod());
            reply(ex, 200, "{\"id\":\"r1\",\"status\":\"running\"}");
        });
        assertThrows(SiteWarmException.WaitTimeout.class, () -> never.warmRuns.waitFor("r1", Duration.ofMillis(20), Duration.ofMillis(1)));
        assertTrue(methods.stream().allMatch("GET"::equals));
    }

    @Test
    void validation() {
        for (String u : new String[] {"http://api.example.test/v1", "https://u:p@api.example.test", "https://x.test/v1?a=1", "api.example.test", ""}) {
            assertThrows(SiteWarmException.class, () -> SiteWarm.builder(KEY, u), u);
        }
        assertDoesNotThrow(() -> SiteWarm.builder(KEY, "http://localhost:8080/v1"));
        for (String k : new String[] {"", "has space", "tab\t"}) {
            var e = assertThrows(SiteWarmException.class, () -> SiteWarm.builder(k, "https://x.test/v1"));
            assertFalse(!k.isEmpty() && e.getMessage().contains(k));
        }
        assertFalse(SiteWarm.builder(KEY, "https://x.test/v1").build().toString().contains(KEY));
    }

    @Test
    void unknownOperationAndMissingPathParam() throws Exception {
        SiteWarm c = client((ex, n) -> reply(ex, 200, "{}"));
        assertTrue(assertThrows(SiteWarmException.class, () -> c.call("nope", Request.of())).getMessage().contains("unknown operation"));
        assertTrue(assertThrows(SiteWarmException.class, () -> c.call("getSite", Request.of())).getMessage().contains("missing path parameter"));
    }

    @Test
    void operationTableMatchesSpec() throws Exception {
        assertEquals(36, Operations.TABLE.size());
        assertEquals(Operation.Idempotency.REQUIRED, Operations.TABLE.get("createWarmRun").idempotency());
        assertEquals(Operation.Idempotency.NONE, Operations.TABLE.get("getWarmRun").idempotency());
        String spec = Files.readString(Path.of("openapi/openapi.yaml"));
        assertFalse(spec.contains("x-open-question"));
        for (var e : Operations.TABLE.entrySet()) assertTrue(spec.contains("operationId: " + e.getKey()), e.getKey());
    }

    @Test
    void retryAfterParsing() {
        assertEquals(Duration.ofSeconds(7), SiteWarm.parseRetryAfter("7"));
        assertNull(SiteWarm.parseRetryAfter("garbage"));
        assertNull(SiteWarm.parseRetryAfter(null));
    }
}
