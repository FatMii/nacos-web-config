# Local Nacos environment

Pinned Docker Compose stack used by this repository for **real-Nacos** work:

- Java adapter integration tests (`NacosConfigSourceIT`)
- Reference-host demos (`java/examples/spring-mvc`)
- Browser end-to-end tests against a live server

This is not a production deployment guide. Images are digests-pinned on `linux/amd64`.

## Quick start

Requires Docker with Compose, and images that can run as `linux/amd64`.

`start.ps1` is optional. It pins Docker Desktop’s `desktop-linux` context and is meant for the maintainer’s machine — **do not assume it works everywhere**. Prefer raw Compose:

```powershell
docker compose --project-name nacos-web-config-local up -d nacos2
# optional: both  # docker compose --project-name nacos-web-config-local up -d nacos2 nacos3
# or the helper, if your Docker has a desktop-linux context:
# powershell -NoProfile -File .\start.ps1 -Service nacos2
```

The script validates Compose, pulls the pinned images, and starts the containers.
A successful exit means **containers were created**, not that Nacos is ready.
Wait until the consoles respond before running tests.

| Service | Role | Console / client |
| --- | --- | --- |
| `nacos2` | Nacos Server **2.5.3** (primary supported line) | http://127.0.0.1:18848/nacos · client `127.0.0.1:18848` |
| `nacos3` | Nacos Server **3.2.3** (adapter-tested only) | http://127.0.0.1:28080 · client `127.0.0.1:28848` |

Ports bind to loopback only. Data lives in named Docker volumes; copying this folder does not copy data. The script never deletes volumes or containers.

On first run it creates a local `.env` with random test placeholders (gitignored). Do not commit or paste `.env` / `.env.auth` contents.

## Authentication overlay

Default Compose has `NACOS_AUTH_ENABLE=false` for everyday local work.

For auth-enabled scenarios use `compose.auth.yaml` with a separate project name and volumes, and put credentials only in local `.env.auth` (gitignored). Treat that stack as a disposable test fixture, not a production example.

## Optional SSE reverse-proxy fixture

The `proxy` Compose profile starts a digest-pinned Nginx that forwards
`http://127.0.0.1:18081` → host `8080` with buffering/cache disabled and a 30s read timeout.
Use it when checking that an SSE stream still works behind a reverse proxy
(see `java/examples/spring-mvc/README.md`).

```powershell
docker compose --profile proxy up -d proxy
docker compose --profile proxy stop proxy   # leaves Nacos instances running
```

This config is a local acceptance fixture, not a copy-paste production gateway.
In production, keep SSE buffering/compression off and set the proxy idle timeout above the application heartbeat.

## Related docs

- Contributor checks: [CONTRIBUTING.md](../CONTRIBUTING.md)
- Example host + E2E: [java/examples/spring-mvc/README.md](../java/examples/spring-mvc/README.md)
- Upstream image tags: https://hub.docker.com/r/nacos/nacos-server/tags
