# Contributing

Thanks for looking. One author maintains this project today. Issues and small PRs are welcome; for behavioral changes, open an issue first - and check [ROADMAP.md](ROADMAP.md) first, because its "Not planned" list is deliberate. Just want to ask something? [Discussions](https://github.com/FatMii/nacos-web-config/discussions) has a Q&A category. Security: see [SECURITY.md](SECURITY.md). Do not file public security issues.

Chinese README: [README.zh-CN.md](README.zh-CN.md)

## Prerequisites

- JDK 17+ and Maven 3.9+ (the machine default `java` may be 8; set `JAVA_HOME`)
- Node.js 18+ (browser SDK and its tests)
- Docker with `docker compose` (only for real-Nacos integration tests)

## Repository map

| Path | What it is |
| --- | --- |
| `java/` | 6 published modules + two reference hosts: `examples/spring-mvc` (public) and `examples/spring-mvc-secured` (`access: authenticated` with Spring Security) |
| `browser/` | TypeScript SDK (workspace `@fatmii/nacos-web-config`) |
| `dev-environment/` | digest-pinned local Nacos 2.5.3 + 3.2.3 (optional Nginx) for real-server IT / E2E |
| `website/` | static landing site (plain HTML/CSS/JS, no build step) |

See also `dev-environment/README.md`.

## Running the checks

```bash
# Java reactor (unit/HTTP tests; *IT is not picked up by Surefire naming defaults)
cd java && mvn verify

# Real-Nacos integration test (start dev-environment/ first)
mvn -pl nacos-adapter -am test -Dtest=NacosConfigSourceIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dnacos.test.server=127.0.0.1:18848

# Browser SDK behavior tests
cd browser && npm ci && npm test && npm run build

# Browser end-to-end against real Nacos (start dev-environment/ first; the
# workflow file lists the environment variables each leg needs)
cd browser && npm run test:e2e
```

CI:

- Every push/PR: `.github/workflows/verify.yml` (Java verify, browser unit tests, SBOM / Trivy). HIGH/CRITICAL findings fail the build.
- Nightly + manual + pushes to `main` / `feat/0.2.0-*`: `.github/workflows/e2e-nacos.yml` (real Nacos 2.5.3 / 3.2.3, adapter IT, example host, Browser SDK + Chromium).
- Nacos 3 Admin token helper: `node dev-environment/scripts/fetch-nacos3-admin-token.mjs`

## Conventions

- Commits: Conventional Commits (`feat|fix|docs|build|ci|chore(scope): …`). The body says what changed in plain language.
- Code comments: only when the *why* is non-obvious.
- Claims in README / package docs must trace to a test or a pinned version pair. Do not document untested behavior.
- Never paste secrets, tokens, passwords, or raw `.env*` content into docs, logs, or issues.

## Releases

Maintainer-only. Version policy is in `CHANGELOG.md` / `CHANGELOG.zh-CN.md`.
Do not publish, tag, or change repo visibility without the owner’s explicit go-ahead.
