# Contributing

Thanks for looking. One author maintains this project today. Issues and small PRs are welcome; for behavioral changes, open an issue first. Security: see [SECURITY.md](SECURITY.md). Do not file public security issues.

Chinese README: [README.zh-CN.md](README.zh-CN.md)

## Prerequisites

- JDK 17+ and Maven 3.9+ (the machine default `java` may be 8; set `JAVA_HOME`)
- Node.js 18+ (browser SDK and its tests)
- Docker with `docker compose` (only for real-Nacos integration tests)

## Repository map

| Path | What it is |
| --- | --- |
| `java/` | 6 published modules + `examples/spring-mvc` reference host |
| `browser/` | TypeScript SDK (workspace `@fatmii/nacos-web-config`) |
| `dev-environment/` | digest-pinned local Nacos 2.5.3 + 3.2.3 (optional Nginx) for real-server IT / E2E |
| `website/` | static landing site (plain HTML/CSS/JS, no build step) |

See also `dev-environment/README.md`.

## Running the checks

```bash
# Java reactor (62 tests; *IT files are excluded by default)
cd java && mvn verify

# Real-Nacos integration test (start dev-environment/ first)
mvn -pl nacos-adapter -am test -Dtest=NacosConfigSourceIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dnacos.test.server=127.0.0.1:18848

# Browser SDK (19 behavior tests)
cd browser && npm ci && npm test && npm run build
```

CI runs the same checks on every push (`.github/workflows/verify.yml`), including SBOM generation and vulnerability/license scanning. HIGH/CRITICAL findings fail the build.

## Conventions

- Commits: Conventional Commits (`feat|fix|docs|build|ci|chore(scope): …`). The body says what changed in plain language.
- Code comments: only when the *why* is non-obvious.
- Claims in README / package docs must trace to a test or a pinned version pair. Do not document untested behavior.
- Never paste secrets, tokens, passwords, or raw `.env*` content into docs, logs, or issues.

## Releases

Maintainer-only. Version policy is in `CHANGELOG.md` / `CHANGELOG.zh-CN.md`.
Do not publish, tag, or change repo visibility without the owner’s explicit go-ahead.
