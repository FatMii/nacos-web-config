# Changelog

All notable changes are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Chinese version: [CHANGELOG.zh-CN.md](./CHANGELOG.zh-CN.md)

On `0.x`, a minor bump may break compatibility; patches are fixes only. Breaking changes are marked **BREAKING**.

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

- Nacos 3.2.3: adapter layer is tested. The full browser path has not been run end-to-end on 3.2.3 yet (Admin tokens are awkward to obtain), so docs do not claim full support.
- Whitelist keys are fixed at startup; adding a key means editing config and restarting. Runtime whitelist changes are not supported.
