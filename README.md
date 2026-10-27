# sdk-java

Java client for the sitewarm API. Models in [`com.sitewarm.sdk.model`](src/main/java/com/sitewarm/sdk/model) are generated from the OpenAPI 3.1 spec in [`openapi/openapi.yaml`](openapi/openapi.yaml) with [OpenAPI Generator](https://openapi-generator.tech); the client (`SiteWarm`) is hand-written on `java.net.http`. Java 17+. Runtime dependency: Jackson.

**Status: pre-release (`0.1.0-SNAPSHOT`, nothing published).** The API and this client can still change. The spec describes the whole planned surface; the service may not offer every operation yet.

```java
SiteWarm c = SiteWarm.builder(System.getenv("SITEWARM_API_KEY"), "https://<api host>/v1").build();

Site site = c.sites.create(new SiteCreate().hostname("example.com"));
WarmRun run = c.warmRuns.create(site.getId(), new WarmRunCreate());
WarmRun done = c.warmRuns.waitFor(run.getId(), Duration.ofMinutes(10), Duration.ofSeconds(2));
for (Result r : c.warmRuns.results(done.getId(), "miss")) {
    System.out.println(r.getUrl());
}
```

A site must be verified before a run is accepted; verification happens outside this API.

## Surface

- `c.sites` (`get`, `create`, `all`) and `c.warmRuns` (`create`, `get`, `cancel`, `results`, `waitFor`).
- Every operation of the spec is reachable with `c.call("<operationId>", Request.of().withPath(...).withBody(...))`; `c.items(...)` iterates list operations (cursors are opaque). The operation table (`Operations`) is generated and a test checks it against the spec.
- Webhook callbacks are server-to-customer and not covered.

## Behavior

- **Auth**: `Authorization: Bearer <key>`. The key is not in `toString()`, exception messages or logs (the SDK never logs), and the client is not `Serializable`. The base URL must be https (http only for localhost) with no credentials or query. The SDK builds its own `HttpClient` with redirects disabled (there is no way to pass one in), so the key never goes to another origin; a 3xx is thrown as `SiteWarmException.Api`.
- **No default base URL**: pass the API host you were given.
- **Retries**: up to 3 (`maxRetries`; a negative value keeps the default) on `429`, `5xx` (not `501`) and network errors, exponential backoff with equal jitter, capped at 30 s. `Retry-After` is honored with a floor of the retry base delay and up to +25% jitter; if it exceeds the cap the error is thrown instead of sleeping. `429` is always retried; `5xx`, network errors and a response body that breaks off only when repeating is safe (`GET`, `HEAD`, `PUT`, `OPTIONS`, or a POST with an `Idempotency-Key`; never `DELETE`/`PATCH`). `timeout(null / zero / negative)` keeps the default (30 s).
- **Idempotency**: every POST that accepts `Idempotency-Key` sends one (random UUID unless you pass one), generated once per call and reused by its retries, so a retried `warmRuns.create` never starts two runs.
- **Limits**: response bodies over 32 MiB throw `SiteWarmException.ResponseTooLarge` (not retried); item iteration throws on a repeated cursor or after 10,000 pages instead of stopping silently; a `limit` query value outside 1..100 throws before the request (empty or 0 = server default).
- **More retry rules**: a `409` `conflict` on a keyed POST is retried (the first attempt may still be running); `idempotency-key-reused` never. Certificate verification failures, unknown hosts and malformed requests (invalid header or URL) are not retried; other network errors are.
- **User-Agent**: `sitewarm-java/<version> (+https://github.com/sitewarm/sdk-java)`; `userAgent(null or "")` keeps it.
- **Errors**: `SiteWarmException.Api` (`status()`, `kind()`, RFC 9457 `code()`, `retryAfter()`, `rateLimitRemaining()`), `SiteWarmException.Connection`, `SiteWarmException.Decode` (`status()`) for a 2xx body that is not JSON (the body is never included), and `SiteWarmException.WaitTimeout` from `waitFor`, which does not cancel the run.

## Development

Build and test with Maven, or in Docker with no local JDK:

```
docker run --rm -v "$PWD":/work -w /work maven:3.9-eclipse-temurin-17 mvn -B test
```

To regenerate after changing `openapi/openapi.yaml` (needs Python 3 with PyYAML):

```
python3 scripts/gen_operations.py && mvn -B -Pgenerate generate-sources
```

CI fails if the generated sources are out of date.

## License

Apache-2.0, see [LICENSE](LICENSE).
