package com.sitewarm.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.OptionalInt;

/** Base class of every exception thrown by this SDK. Messages never contain the API key. */
public class SiteWarmException extends RuntimeException {
    public SiteWarmException(String message) {
        super(message);
    }

    public SiteWarmException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The request never produced an HTTP response (DNS, TLS, reset, timeout). */
    public static final class Connection extends SiteWarmException {
        private final boolean timeout;

        public Connection(String message, boolean timeout, Throwable cause) {
            super(message, cause);
            this.timeout = timeout;
        }

        public boolean isTimeout() {
            return timeout;
        }
    }

    /** The response body was larger than the SDK reads (32 MiB). Not retried. */
    public static final class ResponseTooLarge extends SiteWarmException {
        private final int limit;

        public ResponseTooLarge(int limit) {
            super("response body is larger than " + limit + " bytes");
            this.limit = limit;
        }

        public int limit() {
            return limit;
        }
    }

    /** {@code WarmRuns.waitFor} gave up. The run keeps going server side and is NOT canceled. */
    public static final class WaitTimeout extends SiteWarmException {
        private final String runId;
        private final String lastStatus;

        public WaitTimeout(String runId, String lastStatus) {
            super("warm run " + runId + " did not finish in time (last status: " + lastStatus + ")");
            this.runId = runId;
            this.lastStatus = lastStatus;
        }

        public String runId() {
            return runId;
        }

        public String lastStatus() {
            return lastStatus;
        }
    }

    /** The API answered with a non-2xx status. */
    public static final class Api extends SiteWarmException {
        public enum Kind { AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, VALIDATION, RATE_LIMITED, SERVER, OTHER }

        private final int status;
        private final JsonNode problem;
        private final String code;
        private final Duration retryAfter;
        private final OptionalInt rateLimitRemaining;

        Api(int status, JsonNode problem, String code, Duration retryAfter, OptionalInt rateLimitRemaining) {
            super(message(status, problem));
            this.status = status;
            this.problem = problem;
            this.code = code;
            this.retryAfter = retryAfter;
            this.rateLimitRemaining = rateLimitRemaining;
        }

        private static String message(int status, JsonNode p) {
            StringBuilder s = new StringBuilder("sitewarm API error ").append(status);
            if (p.path("title").isTextual()) s.append(": ").append(p.get("title").asText());
            if (p.path("detail").isTextual()) s.append(" (").append(p.get("detail").asText()).append(')');
            return s.toString();
        }

        public int status() {
            return status;
        }

        /** The RFC 9457 problem body. */
        public JsonNode problem() {
            return problem;
        }

        /** The problem type slug (for example {@code site-not-verified}); null when unknown. */
        public String code() {
            return code;
        }

        /** Parsed {@code Retry-After}; null when absent. */
        public Duration retryAfter() {
            return retryAfter;
        }

        public OptionalInt rateLimitRemaining() {
            return rateLimitRemaining;
        }

        public Kind kind() {
            if (status == 401) return Kind.AUTHENTICATION;
            if (status == 403) return Kind.PERMISSION;
            if (status == 404) return Kind.NOT_FOUND;
            if (status == 409) return Kind.CONFLICT;
            if (status == 400 || status == 422) return Kind.VALIDATION;
            if (status == 429) return Kind.RATE_LIMITED;
            if (status >= 500) return Kind.SERVER;
            return Kind.OTHER;
        }
    }
}
