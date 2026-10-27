package com.sitewarm.sdk;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LowsTest {
    static final String KEY = "swk_secret_key";

    interface Handler {
        void handle(HttpExchange ex, int n) throws IOException;
    }

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final List<Duration> pauses = new ArrayList<>();
    final List<String> userAgents = new ArrayList<>();

    SiteWarm.Builder builder(Handler h) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
                h.handle(ex, calls.incrementAndGet());
            } finally {
                ex.close();
            }
        });
        server.start();
        return SiteWarm.builder(KEY, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .retryDelays(Duration.ZERO, Duration.ZERO).sleeper(pauses::add);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    static void reply(HttpExchange ex, int status, String body, String... headers) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) ex.getResponseHeaders().add(headers[i], headers[i + 1]);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }

    static Request site() {
        return Request.of().withPath("siteId", "s1");
    }

    static String problem(String slug) {
        return "{\"type\":\"https://x.test/problems/" + slug + "\",\"title\":\"c\",\"status\":409}";
    }

    @Test
    void conflictOnKeyedPostIsRetriedButKeyReusedIsNot() throws Exception {
        for (String[] c : new String[][] {{"conflict", "3"}, {"idempotency-key-reused", "1"}}) {
            calls.set(0);
            SiteWarm sw = builder((ex, n) -> reply(ex, 409, problem(c[0]))).maxRetries(2).build();
            SiteWarmException.Api e = assertThrows(SiteWarmException.Api.class,
                    () -> sw.call("createSite", Request.of().withBody(java.util.Map.of("hostname", "example.com"))));
            assertEquals(c[0], e.code());
            assertEquals(Integer.parseInt(c[1]), calls.get(), c[0]);
            stop();
        }
    }

    @Test
    void conflictOnUnkeyedOperationIsNotRetried() throws Exception {
        SiteWarm sw = builder((ex, n) -> reply(ex, 409, problem("conflict"))).maxRetries(2).build();
        assertThrows(SiteWarmException.Api.class, () -> sw.call("deleteSite", site()));
        assertEquals(1, calls.get());
    }

    @Test
    void networkErrorClasses() {
        assertFalse(SiteWarm.isTransient(new java.net.UnknownHostException("nope.invalid")));
        assertFalse(SiteWarm.isTransient(new java.net.ConnectException("x") {{ initCause(new java.nio.channels.UnresolvedAddressException()); }}));
        javax.net.ssl.SSLHandshakeException hs = new javax.net.ssl.SSLHandshakeException("handshake failed");
        hs.initCause(new java.security.cert.CertificateException("PKIX path building failed"));
        assertFalse(SiteWarm.isTransient(hs));
        assertFalse(SiteWarm.isTransient(new IOException("x", new java.security.cert.CertPathValidatorException("bad"))));
        assertFalse(SiteWarm.isTransient(new javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")));
        assertFalse(SiteWarm.isTransient(new IOException("invalid header field")));
        assertTrue(SiteWarm.isTransient(new IOException("Connection reset")));
        assertTrue(SiteWarm.isTransient(new java.net.ConnectException("refused")));
    }

    @Test
    void unknownHostIsNotRetriedButRefusedConnectionIs() throws Exception {
        SiteWarm bad = SiteWarm.builder(KEY, "https://nonexistent.invalid/v1").maxRetries(2)
                .retryDelays(Duration.ZERO, Duration.ZERO).sleeper(pauses::add).build();
        assertThrows(SiteWarmException.Connection.class, () -> bad.call("getSite", site()));
        assertTrue(pauses.isEmpty(), "unknown host was retried");

        int port;
        try (var s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        SiteWarm refused = SiteWarm.builder(KEY, "http://127.0.0.1:" + port + "/v1").maxRetries(2)
                .retryDelays(Duration.ZERO, Duration.ZERO).sleeper(pauses::add).build();
        assertThrows(SiteWarmException.Connection.class, () -> refused.call("getSite", site()));
        assertEquals(2, pauses.size());
    }

    @Test
    void retryAfterZeroWaitsTheBaseWithJitter() throws Exception {
        SiteWarm sw = builder((ex, n) -> {
            if (n == 1) reply(ex, 429, "{}", "Retry-After", "0");
            else reply(ex, 200, "{\"id\":\"s1\"}");
        }).retryDelays(Duration.ofMillis(200), Duration.ofSeconds(5)).build();
        sw.call("getSite", site());
        assertEquals(1, pauses.size());
        long ms = pauses.get(0).toMillis();
        assertTrue(ms >= 200 && ms <= 250, "waited " + ms);
    }

    @Test
    void pastHttpDateIsFlooredToo() throws Exception {
        SiteWarm sw = builder((ex, n) -> {
            if (n == 1) reply(ex, 429, "{}", "Retry-After", "Mon, 01 Jan 2001 00:00:00 GMT");
            else reply(ex, 200, "{\"id\":\"s1\"}");
        }).retryDelays(Duration.ofMillis(200), Duration.ofSeconds(5)).build();
        sw.call("getSite", site());
        assertTrue(pauses.get(0).toMillis() >= 200);
    }

    @Test
    void retryAfterAboveTheCapIsSurfacedNotSlept() throws Exception {
        SiteWarm sw = builder((ex, n) -> reply(ex, 429, "{}", "Retry-After", "60"))
                .retryDelays(Duration.ofMillis(200), Duration.ofSeconds(5)).build();
        SiteWarmException.Api e = assertThrows(SiteWarmException.Api.class, () -> sw.call("getSite", site()));
        assertEquals(Duration.ofSeconds(60), e.retryAfter());
        assertEquals(1, calls.get());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void nonJsonSuccessIsATypedDecodeError() throws Exception {
        SiteWarm sw = builder((ex, n) -> reply(ex, 200, "<html>secret-proxy-page</html>")).build();
        SiteWarmException.Decode e = assertThrows(SiteWarmException.Decode.class, () -> sw.call("getSite", site()));
        assertEquals(200, e.status());
        assertFalse(e.getMessage().contains("secret-proxy-page"));
        assertNull(e.getCause());
        assertInstanceOf(SiteWarmException.class, e);
    }

    @Test
    void userAgentCarriesVersionAndUrlAndEmptyKeepsTheDefault() throws Exception {
        String def = "sitewarm-java/" + SiteWarm.VERSION + " (+https://github.com/sitewarm/sdk-java)";
        builder((ex, n) -> reply(ex, 200, "{\"id\":\"s1\"}")).build().call("getSite", site());
        stop();
        builder((ex, n) -> reply(ex, 200, "{\"id\":\"s1\"}")).userAgent("").build().call("getSite", site());
        stop();
        builder((ex, n) -> reply(ex, 200, "{\"id\":\"s1\"}")).userAgent("mine/1").build().call("getSite", site());
        assertEquals(List.of(def, def, "mine/1"), userAgents);
    }

    @Test
    void versionConstantMatchesThePom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<artifactId>sitewarm-sdk</artifactId>\n  <version>" + SiteWarm.VERSION + "</version>"));
    }

    @Test
    void basePathKeepsPercentEncoding() throws Exception {
        SiteWarm sw = SiteWarm.builder(KEY, "https://x.test/a%2Fb/v1").build();
        assertTrue(sw.toString().contains("https://x.test/a%2Fb/v1"));
    }
}
