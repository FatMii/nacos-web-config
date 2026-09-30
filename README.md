<h1 align="center">Nacos Web Config</h1>

<p align="center">
  <em>Let the browser safely receive only the Nacos JSON configs you allow. Change a config, and the page updates.</em>
</p>

<p align="center"><a href="./README.zh-CN.md">中文</a></p>

<p align="center">
  <a href="https://fatmii.github.io/nacos-web-config/"><img alt="Website" src="https://img.shields.io/badge/website-live-0A7EA4?logo=githubpages&logoColor=white"></a>
  &nbsp;
  <a href="https://github.com/FatMii/nacos-web-config/actions/workflows/verify.yml"><img alt="CI" src="https://github.com/FatMii/nacos-web-config/actions/workflows/verify.yml/badge.svg"></a>
  &nbsp;
  <a href="https://central.sonatype.com/artifact/io.github.fatmii.nacoswebconfig/nacos-web-config-spring-boot-starter"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.fatmii.nacoswebconfig/nacos-web-config-spring-boot-starter?label=Maven%20Central"></a>
  &nbsp;
  <a href="https://www.npmjs.com/package/@fatmii/nacos-web-config"><img alt="npm" src="https://img.shields.io/npm/v/@fatmii/nacos-web-config.svg?logo=npm"></a>
  &nbsp;
  <a href="./LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/License-Apache--2.0-blue.svg"></a>
  &nbsp;
  <a href="#"><img alt="Java" src="https://img.shields.io/badge/Java-17-orange"></a>
  &nbsp;
  <a href="#"><img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen"></a>
</p>

> **0.1.0** is on Maven Central and npm. The quick start below installs from those registries.

```text
+-------+   watch    +---------------------+   HTTP/SSE  +-------------+            +------+
| Nacos | ---------> | Spring Boot Starter | ----------> | Browser SDK | -------->  | Page |
+-------+            +---------------------+ same-origin +-------------+            +------+
```

---

# Table of contents

- [What it is](#what-it-is)
- [Why you need it](#why-you-need-it)
- [Features](#features)
- [Quick start](#quick-start)
- [Local demo](#local-demo)
- [Configuration reference](#configuration-reference)
- [Compatibility](#compatibility)
- [Limitations](#limitations)
- [Changelog](./CHANGELOG.md)
- [Security](#security)
- [License](#license)

---

# What it is

- A Spring Boot **starter** you add to an existing app (no extra service to run)
- A small **browser SDK** (TypeScript ESM, no runtime dependencies)
- A **read-only** path: the page only gets the JSON configs you explicitly allow

---

# Why you need it

Banners, feature flags, and campaign copy often live in Nacos. Ops wants the live page to change as soon as they publish—not after the next frontend release.

But the page should not talk to Nacos itself:

- **Do not put the password in the frontend.** Anyone can see it in DevTools. A stolen Nacos account can change or delete configs, not only read them.
- **You cannot open just one config.** Nacos authorizes by namespace. There is no switch for “the page may read only this one; leave the rest alone.”
- **Opening it to the public is risky.** Anyone can hammer it. If it goes down, every backend that depends on it is affected.

So let your Spring Boot app talk to Nacos, and let the page talk only to your app. You list which names the page may see; the backend checks them and pushes updates.

| Scenario | Homegrown polling API | nacos-web-config |
| --- | --- | --- |
| Who sees what | You write auth and mapping | **Only the names you list** |
| Ops publishes bad JSON | Rules are yours; the page often breaks | **Drop bad data; keep the last good value** |
| Show updates soon after publish | Keep polling, or build push yourself | **Backend pushes changes** |
| Nacos is briefly down | You invent the behavior | **Values on the page are not cleared** |
| Integration cost | Cache, protocol, tests | **Dependency + YAML + `subscribe()`** |

### When you do not need this library

If you do not need live updates, a plain GET plus a timer is enough.

---

# Features

## Only configs you allow

In YAML `exposures`, you map a page-facing name (like `ui`) to one Nacos config. The frontend subscribes by that name only. It never sees the Nacos address, group, or dataId.

You must also choose who can connect: `public` (anyone can read the allowed configs) or `authenticated` (signed-in users only). If you skip that, the app fails to start.

## Bad data does not break the page

Updates must be JSON objects under your size limit (`max-bytes`). Anything else is dropped, and the page keeps the last good value. Identical content is not pushed again.

## Push updates after publish

The backend pushes over SSE—no frontend polling timer. One connection can subscribe to up to 32 names. A full snapshot comes first, then later changes. If events arrive out of order, the SDK rejects them and refetches a full snapshot so the page does not drift.

## Survives disconnects and outages

If Nacos is temporarily unreachable, values on the page are not cleared. They are marked stale and refreshed when Nacos is back. The SDK reconnects on its own, and refreshes when a background tab becomes visible again. A slow browser only hurts its own connection.

---

# Quick start

### Prerequisites

- JDK 17+, Maven, Node.js (CI pins Temurin 17 / Node 22)
- A Spring Boot 3.x **servlet/MVC** application
- Nacos Server 2.5.3 (3.2.3 adapter-tested only; see [Compatibility](#compatibility))

### 1. Backend: add the starter and declare the whitelist

```xml
<dependency>
  <groupId>io.github.fatmii.nacoswebconfig</groupId>
  <artifactId>nacos-web-config-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
# application.yml
nacos-web-config:
  enabled: true             # off unless explicitly enabled
  access: public            # required: public | authenticated
  nacos:
    server-addr: 127.0.0.1:8848
  exposures:                # whitelist: logical key -> fixed Nacos coordinates
    ui:
      group: WEB_DEMO
      data-id: web-demo.public.json
      max-bytes: 65536
```

This exposes the SSE endpoint at `/_web-config/v1/stream` (override with `path`).

**Choosing `access`**

- **`public`**: anyone who can reach the endpoint may read the whitelisted content (read-only, never the whole store). Use it for announcements strangers may see.
- **`authenticated`**: a stream opens only when the request already has a signed-in identity. The starter checks `request.getUserPrincipal()` and does not care which component set it (Spring Security, JWT/session filter, container auth). It never logs anyone in.
- If nothing sets a principal, every connection gets 401 (fail-closed on purpose).
- Access is per stream, not per key.

### 2. Frontend: subscribe

```bash
npm install @fatmii/nacos-web-config
```

```ts
import { createWebConfig } from '@fatmii/nacos-web-config'

const config = createWebConfig({
  // endpoint defaults to '/_web-config/v1/stream'; decode is optional
  definitions: {
    ui: { fallback: { announcement: '' } },
  },
})

const unsubscribe = config.subscribe('ui', state => {
  renderAnnouncement(state.value?.announcement ?? '')
})
// config.get('ui') reads current state without a network call;
// the shared connection opens on the first subscribe().
```

For a stricter page, add a `decode` guard. Rejected values keep last-known-good, mark the key `invalid`, and surface via `onError` (or one `console.warn` per key if `onError` is absent):

```ts
ui: {
  fallback: { announcement: '' },
  decode(value) {
    if (typeof value !== 'object' || value === null) throw new Error('ui must be an object')
    return { announcement: String((value as { announcement?: unknown }).announcement ?? '') }
  },
},
```

---

# Local demo

You need **JDK 17**, **Maven**, and **Docker** that can run `linux/amd64` images (Docker Desktop, OrbStack, etc.). `dev-environment/start.ps1` is a maintainer helper pinned to the `desktop-linux` context — **it will not work on every machine**. Prefer Compose and only Nacos 2.5.3:

```powershell
cd dev-environment
docker compose --project-name nacos-web-config-local up -d nacos2
# wait until http://127.0.0.1:18848/nacos responds
```

Then start the reference host (port `18848`, not the usual `8848`):

```powershell
cd ..\java
$env:NACOS_SERVER_ADDR='127.0.0.1:18848'
mvn -pl examples/spring-mvc -am spring-boot:run
```

Open `http://127.0.0.1:8080/`, publish `web-demo.public.json` under group `WEB_DEMO` in the Nacos console (e.g. `{"announcement":"hello"}`). The page should update within a few seconds without a reload.

More detail: [`java/examples/spring-mvc/README.md`](./java/examples/spring-mvc/README.md) and [`dev-environment/README.md`](./dev-environment/README.md).

---

# Configuration reference

All keys sit under `nacos-web-config`. An invalid value **fails startup with a precise message**; nothing is silently defaulted. Full config with defaults below (`//` is documentation only — use `#` in real YAML):

```yaml
nacos-web-config:
  enabled: false                    // master switch; module is off unless true
  access: public                    // required: public | authenticated; see “Choosing access” above
  path: /_web-config                // base path; stream is <path>/v1/stream; must start with / and must not end with /

  exposures:                        // whitelist; at least one entry; alias /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
    ui:                             // page-facing name the SDK subscribes to; rename or add entries as needed
      data-id: web.ui.json          // required: fixed Nacos dataId behind this name
      group: DEFAULT_GROUP          // fixed Nacos group
      max-bytes: 65536              // per-value cap; oversized or non-JSON-object content is dropped; page keeps last good value

  nacos:
    server-addr: 127.0.0.1:8848     // required when the starter builds the client; host:port, comma-separated; omit if reusing an existing client
    namespace: public               // single namespace this instance serves
    username: ""                    // fill when Nacos auth is on; credentials stay on the backend
    password: ""
    timeout: 3s                     // timeout on Nacos client operations

  source:
    mode: auto                      // auto: reuse the only ConfigService bean, else build from nacos.* (fails if several beans); bean: reuse bean-name; managed: always build
    bean-name: ""                   // required when mode=bean: which ConfigService bean to reuse

  stream:                           // defaults fit small-to-medium traffic; tune at scale
    heartbeat: 15s                  // SSE ping interval
    connection-timeout: 30m         // server closes the stream after this; SDK reconnects and refetches a snapshot
    max-connections: 1000           // concurrent stream cap; excess gets 429
    max-keys-per-connection: 32     // max logical names per browser connection
    max-pending-bytes-per-connection: 1MB  // slow-consumer budget; over budget drops that connection only

  startup:
    fail-fast: false                // true: abort startup if whitelisted configs are unavailable; default boots and uses fallback/stale until Nacos is up
```

### Prefer aggregate keys

One logical key maps to one fixed Nacos config. Adding a key means editing `application.yml` and restarting, so every widening of the public surface leaves a deploy trail. For content that changes often, expose one coarse key (e.g. `homepage`) and put the fields in its JSON body. Ops edits take effect live with no new exposure and no restart. Split keys only on real boundaries: different access level, owner, or size budget.

---

# Compatibility

| Dependency | Version | Notes |
| --- | --- | --- |
| Java | 17+ | Spring Boot 3.x, servlet/MVC |
| Nacos client | 2.5.3 | single supported client line |
| Nacos server | 2.5.3 | full chain verified (Java tests + browser E2E) |
| Nacos server | 3.2.3 | Adapter tested; full browser path not claimed yet |
| Browsers | modern | `fetch` + `ReadableStream`; no EventSource fallback |

---

# Limitations

- Nacos 3.2.3: adapter tests pass; full browser path is not claimed yet (Admin token is awkward to automate there).
- Servlet MVC only; no WebFlux.
- Values must be JSON objects under `max-bytes`.
- ESM-only SDK; older browsers need your own bundler/polyfill.
- No admin UI; create and edit configs in the Nacos console.

---

# Security

Vulnerability reporting and the product security model: [SECURITY.md](SECURITY.md).
Do not file public issues for security problems.

---

# License

[Apache License 2.0](./LICENSE). Third-party notices in [NOTICE](./NOTICE).
