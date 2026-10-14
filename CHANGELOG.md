# Changelog

All notable changes are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Chinese version: [CHANGELOG.zh-CN.md](./CHANGELOG.zh-CN.md)

On `0.x`, a minor bump may break compatibility; patches are fixes only. Breaking changes are marked **BREAKING**.

## 0.2.0 — Unreleased

### Added

- Optional operational surface for hosts that run Spring Boot Actuator: a `nacosWebConfig` health component (whether Nacos is reachable, plus the state each key is serving) and three metrics — `nacos.webconfig.stream.connections`, `nacos.webconfig.stream.events` (by frame kind) and `nacos.webconfig.rejections` (by reason code, never content). Actuator stays an optional dependency: a host without it is unchanged and still starts.
- New read-only seams behind it: `ConfigSource.available()`, `ConfigRuntime.aliases()`, `ConfigRuntime.sourceAvailable()`, and the `WebConfigStreamStatus` view of the SSE engine.
- `java/examples/spring-mvc-secured`: a runnable `access: authenticated` host. An unauthenticated `Accept: text/event-stream` request gets `401` while ordinary page requests still reach the login form.
- The demo host now subscribes through the published `@fatmii/nacos-web-config` SDK instead of a hand-written `EventSource`, and `verify.yml` fails if the copy bundled in the example drifts from the SDK build.
- `.github/workflows/e2e-nacos.yml`: browser end-to-end against real Nacos **2.5.3** and **3.2.3**, covering three paths — normal push, reconnect after the host restarts, and delivery through an Nginx proxy without buffering. Runs nightly, on demand, and on pushes.
- `dev-environment/scripts/fetch-nacos3-admin-token.mjs` obtains a short-lived Nacos 3 Admin token for those runs, including the first-boot administrator bootstrap.
- `.github/workflows/publish-github-packages.yml` publishes the Maven and npm artifacts to GitHub Packages as well.
- Documentation measured rather than asserted: gateway and reverse-proxy behaviour (which settings actually bite, and the ones we could not reproduce a problem for), the front-end dev-server proxy config with React and Vue wiring, Chinese content and the Windows publishing pitfall, a table of who decides which dependency versions end up in your application, and the boundary of the CVE-2026-47890 exemption in `SECURITY.md`.
- Website: interactive pipeline diagram, a real push recording instead of a simulated demo, and a bilingual changelog.

### Changed

- Compatibility: the full browser chain is now claimed on Nacos Server 3.2.3, backed by the E2E workflow above.
- Jackson ahead-pinned from 2.18.11 to 2.21.7 to clear the HIGH/CRITICAL gate on the Spring Boot 3.5 line. CVE-2026-47890 keeps a documented exemption instead: no OSS patch exists for `spring-webmvc` 6.2.x, this library pushes JSON payloads over SSE only, and `WebConfigNoViewFragmentOutputTest` fails the build if a view object ever reaches that path.
- GitHub Pages deploys only after the Verify workflow succeeds.

### Fixed

- An ordinary SSE client disconnect or broken pipe no longer escalates through `completeWithError`; the session is closed quietly, and when Logback is present a turbo filter also drops the Tomcat/`dispatcherServlet` ERROR lines those disconnects used to produce.
- `examples/spring-mvc` now builds an executable jar (the `repackage` goal is bound as an execution), which is what the E2E workflow runs.
- Two end-to-end assertions that could pass or fail for the wrong reason were rewritten to measure what they claim: the proxy scenario passes an explicit heartbeat interval, and the decoder scenario asserts error codes instead of a delivery count.

## 0.1.0 — 2026-09-28

### Added

- Backend core: whitelist, JSON object validation, size limits, discard bad data while keeping the last good copy, snapshot and push.
- Nacos adapter: listen for config, detect deletes, recover when the connection drops. Client pinned to 2.5.3; adapter tests run against Server 2.5.3 / 3.2.3.
- Spring MVC: same-origin HTTP/SSE (snapshot first, then change pushes), heartbeats, connection cap (429 when full), slow clients only lose their own connection.
- Spring Boot auto-config / starter / starter-test: YAML binding; `access` must be `public` or `authenticated`; unit tests can run without a real Nacos.
- Browser SDK `@fatmii/nacos-web-config`: subscribe, get values, reconnect on drop; reject out-of-order sequence numbers and refetch the snapshot.
- WARN on rejected content (key, error code, content hash — never the raw body).
- Apache-2.0; published to Maven Central and npm.

### Changed

- SDK: `decode` and `endpoint` are optional (`endpoint` defaults to `/_web-config/v1/stream`).
- When decode fails and no `onError` is passed, at most one `console.warn` per key so failures are not silent.
- Spring Boot bumped to 3.5.16, Jackson to 2.18.11; tomcat-embed pinned to 10.1.60.
- CI fails the build on HIGH/CRITICAL findings.
- Gave timing-sensitive HTTP tests more timeout headroom so busy machines trip fewer false failures.

### Known limitations

- Whitelist keys are fixed at startup; adding a key means editing config and restarting. Runtime whitelist changes are not supported.
