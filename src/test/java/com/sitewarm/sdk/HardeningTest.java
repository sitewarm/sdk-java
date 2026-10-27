package com.sitewarm.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HardeningTest {
    static final String KEY = "swk_secret_key";

    interface Handler {
        void handle(HttpExchange ex, int n) throws IOException;
    }

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();

    SiteWarm client(Handler h, Consumer<SiteWarm.Builder> cfg) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                h.handle(ex, calls.incrementAndGet());
            } finally {
                try {
                    ex.close();
                } catch (RuntimeException ignored) {
                    // a deliberately short body makes close() complain
                }
            }
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

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }

    /** Promises 100 bytes, sends 5 and drops the connection. */
    static void broken(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, 100);
        ex.getResponseBody().write("{\"id\"".getBytes(StandardCharsets.UTF_8));
        ex.getResponseBody().flush();
        throw new IOException("drop"); // the wrapper closes the exchange with fewer bytes than promised
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    static Request site() {
        return Request.of().withPath("siteId", "s1");
    }

    @Test
    void nonPositiveTimeoutAndNegativeRetriesKeepDefaults() throws Exception {
        for (Duration d : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), null}) {
            SiteWarm c = client((ex, n) -> reply(ex, 200, "{\"id\":\"s1\"}"), b -> b.timeout(d));
            assertEquals("s1", c.call("getSite", site()).get("id").asText()); // a zero timeout would throw
            stop();
        }
        calls.set(0);
        SiteWarm c = client((ex, n) -> reply(ex, 503, "{}"), b -> b.maxRetries(-1));
        assertThrows(SiteWarmException.Api.class, () -> c.call("getSite", site()));
        assertEquals(4, calls.get()); // default of 3 retries
    }

    @Test
    void bodyReadErrorIsRetriedOnRepeatableCalls() throws Exception {
        SiteWarm c = client((ex, n) -> {
            if (n == 1) broken(ex, 200);
            else reply(ex, 200, "{\"id\":\"s1\"}");
        });
        assertEquals("s1", c.call("getSite", site()).get("id").asText());
        assertEquals(2, calls.get());
    }

    @Test
    void bodyReadErrorOnDeleteIsNotRetried() throws Exception {
        SiteWarm c = client((ex, n) -> broken(ex, 200), b -> b.maxRetries(2));
        assertThrows(SiteWarmException.Connection.class, () -> c.call("deleteSite", site()));
        assertEquals(1, calls.get());
    }

    @Test
    void bodyReadErrorAfter429IsRetriedForAnyMethod() throws Exception {
        SiteWarm c = client((ex, n) -> {
            if (n == 1) broken(ex, 429);
            else reply(ex, 200, "{}");
        });
        c.call("deleteSite", site());
        assertEquals(2, calls.get());
    }

    @Test
    void oversizedBodyIsTypedAndNotRetried() throws Exception {
        SiteWarm c = client((ex, n) -> {
            ex.sendResponseHeaders(200, 0); // chunked
            byte[] chunk = new byte[1 << 20];
            try {
                for (int i = 0; i < 40; i++) ex.getResponseBody().write(chunk);
            } catch (IOException closedByClient) {
                // expected: the SDK stops reading
            }
        });
        assertThrows(SiteWarmException.ResponseTooLarge.class, () -> c.call("getSite", site()));
        assertEquals(1, calls.get());
    }

    @Test
    void readLimitedAllowsExactlyTheLimit() throws Exception {
        assertEquals(10, SiteWarm.readLimited(new java.io.ByteArrayInputStream(new byte[10]), 10).length);
        assertThrows(SiteWarmException.ResponseTooLarge.class,
                () -> SiteWarm.readLimited(new java.io.ByteArrayInputStream(new byte[11]), 10));
    }

    @Test
    void onlyRepeatableMethodsRetryServerErrors() throws Exception {
        record Case(String op, Request req, int want) { }
        List<Case> cases = List.of(
                new Case("getSite", site(), 3),
                new Case("replaceUrlSet", site().withPath("urlSetId", "u1"), 3),
                new Case("deleteSite", site(), 1),
                new Case("updateSite", site(), 1));
        for (Case cs : cases) {
            calls.set(0);
            SiteWarm c = client((ex, n) -> reply(ex, 503, "{}"), b -> b.maxRetries(2));
            assertThrows(SiteWarmException.Api.class, () -> c.call(cs.op(), cs.req()), cs.op());
            assertEquals(cs.want(), calls.get(), cs.op());
            stop();
        }
        // 429 is retried for every method
        calls.set(0);
        SiteWarm c = client((ex, n) -> {
            ex.getResponseHeaders().add("Retry-After", "0");
            if (n < 3) reply(ex, 429, "{}");
            else reply(ex, 200, "{}");
        });
        c.call("deleteSite", site());
        assertEquals(3, calls.get());
    }

    @Test
    void paginationErrorsOnRepeatedCursorAndAtPageCap() throws Exception {
        SiteWarm cyc = client((ex, n) -> {
            String q = ex.getRequestURI().getQuery();
            String next = q != null && q.contains("cursor=b") ? "a" : "b";
            reply(ex, 200, "{\"data\":[{\"id\":\"x\"}],\"page\":{\"has_more\":true,\"next_cursor\":\"" + next + "\"}}");
        });
        var e = assertThrows(SiteWarmException.class, () -> cyc.sites.all().forEach(s -> { }));
        assertTrue(e.getMessage().contains("did not advance"));
        stop();

        calls.set(0);
        SiteWarm endless = client((ex, n) -> reply(ex, 200,
                "{\"data\":[],\"page\":{\"has_more\":true,\"next_cursor\":\"c" + n + "\"}}"));
        var cap = assertThrows(SiteWarmException.class,
                () -> endless.items("listSites", Request.of(), com.sitewarm.sdk.model.Site.class, 5).forEach(s -> { }));
        assertTrue(cap.getMessage().contains("stopped after 5 pages"));
        assertEquals(5, calls.get());
    }

    @Test
    void listLimitIsValidatedBeforeSending() throws Exception {
        List<String> queries = new ArrayList<>();
        SiteWarm c = client((ex, n) -> {
            queries.add(String.valueOf(ex.getRequestURI().getQuery()));
            reply(ex, 200, "{\"data\":[],\"page\":{\"has_more\":false}}");
        });
        for (String bad : new String[] {"-1", "101", "x", "1.5"}) {
            assertThrows(SiteWarmException.class, () -> c.call("listSites", Request.of().withQuery("limit", bad)), bad);
        }
        assertEquals(0, calls.get());
        for (String ok : new String[] {"1", "100", "0", ""}) c.call("listSites", Request.of().withQuery("limit", ok));
        assertEquals(List.of("limit=1", "limit=100", "null", "null"), queries);
    }

    @Test
    void keyIsNotInToStringOrErrors() throws Exception {
        SiteWarm c = client((ex, n) -> reply(ex, 401, "{\"title\":\"T\"}"), b -> b.maxRetries(0));
        assertFalse(c.toString().contains(KEY));
        assertFalse(c.sites.toString().contains(KEY));
        assertFalse(c.warmRuns.toString().contains(KEY));
        assertFalse(SiteWarm.builder(KEY, "https://x.test/v1").toString().contains(KEY));
        var e = assertThrows(SiteWarmException.Api.class, () -> c.call("getSite", site()));
        assertFalse(e.toString().contains(KEY));
        assertFalse(java.io.Serializable.class.isAssignableFrom(SiteWarm.class), "client must not be serializable");
    }
}
