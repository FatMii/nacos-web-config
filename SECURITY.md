# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| latest `0.x` on `main` | yes |
| older `0.x` releases | no (`0.x` has no separate security patch line; upgrade to the latest `0.x`) |

0.1.0 was published to Maven Central and npm on 2026-09-29. Security fixes target the latest minor series.

## Reporting a Vulnerability

Use a private GitHub security advisory. Do not open a public issue:

<https://github.com/FatMii/nacos-web-config/security/advisories/new>

Maintainers acknowledge within 7 days. During `0.x` there is no fixed SLA for a patch. After a fix, a new version is published to npm / Maven Central first; a GHSA may follow when needed.

## Product Security Model

Read this before filing, to decide whether the issue is in scope:

- Browsers can only read keys declared in the `exposures` whitelist. Nacos group / dataId / namespace never reach the browser.
- `access: authenticated` delegates auth to the host security chain. If the host leaves the SSE endpoint unauthenticated while using `authenticated`, that is a host misconfiguration.
- Delivered content must be a JSON object within the byte cap (`max-bytes`). Non-JSON or oversized content does not replace LKG.
- SSE responses use `no-store`. Subscription keys are format-checked. Connection count and per-connection pending bytes are capped; slow clients that exceed the budget are disconnected.
- The product does not persist config bodies to disk. Caches stay in memory.

### Boundaries of the CVE-2026-47890 exemption

`spring-webmvc` 6.2.x is affected by CVE-2026-47890 (stream corruption when Server-Sent Events carry
view fragments). No patch exists on the Spring Boot 3.5 line — the fix is Spring Framework 7.0.9,
which requires Spring Boot 4 — so `.trivyignore` records an exemption for this repository instead.

The exemption describes this library, not every host that uses it. What this library pushes is
`SseEmitter` events whose payload is a JSON string, and it contains no view or template layer;
`WebConfigNoViewFragmentOutputTest` fails the build if a view object ever reaches the SSE path.

**If your own application renders Spring MVC view fragments, or pushes HTML fragments over its own
SSE endpoints, that application is exposed whether or not this library is present.** Upgrade it to
Spring Boot 4; the exemption here does not cover it.

Out of scope: vulnerabilities in the host application, in Nacos Server itself, or that depend only on the user’s environment (for example a misconfigured Nginx).
