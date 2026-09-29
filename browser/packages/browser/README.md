# @fatmii/nacos-web-config

Browser SDK for [Nacos Web Config](https://github.com/FatMii/nacos-web-config). Receives whitelisted
Nacos JSON over a same-origin SSE stream from your Spring Boot backend. TypeScript ESM, no runtime
dependencies.

```text
+-------+   watch    +---------------------+   HTTP/SSE  +----------+            +------+
| Nacos | ---------> | Spring Boot starter | ----------> | this SDK | -------->  | Page |
+-------+            +---------------------+ same-origin +----------+            +------+
```

The browser never talks to Nacos and never sees Nacos coordinates. Your backend declares which
logical keys are public, validates every update, and pushes only what passed.

## Install

```bash
npm install @fatmii/nacos-web-config
```

Requires a backend running
[`nacos-web-config-spring-boot-starter`](https://central.sonatype.com/artifact/io.github.fatmii.nacoswebconfig/nacos-web-config-spring-boot-starter)
with at least one `exposures` entry. Browser needs `fetch` + `ReadableStream`; there is no
`EventSource` fallback. ESM-only — bundle it yourself for older targets.

## Use it

```ts
import { createWebConfig } from '@fatmii/nacos-web-config'

const config = createWebConfig({
  // endpoint defaults to '/_web-config/v1/stream', the starter's default route
  definitions: {
    ui: { fallback: { announcement: '' } },
  },
})

const unsubscribe = config.subscribe('ui', state => {
  renderAnnouncement(state.value?.announcement ?? '')
})
```

`subscribe()` fires the listener immediately with the current state, then again on every change.
The connection opens on the first `subscribe()`; `get()` never triggers network activity.

Add a `decode` guard when you want a rejected update to keep the value already on screen:

```ts
ui: {
  fallback: { announcement: '' },
  decode(value) {
    if (typeof value !== 'object' || value === null) throw new Error('ui must be an object')
    return { announcement: String((value as { announcement?: unknown }).announcement ?? '') }
  },
}
```

## API

### `createWebConfig(options)`

| Option | Default | Notes |
| --- | --- | --- |
| `definitions` | — (required) | One entry per whitelisted logical key; keys must match the backend's `exposures` aliases. |
| `definitions[key].fallback` | none | Value served before the first remote snapshot and when the source is unavailable. Omit it and `value` stays `undefined`. |
| `definitions[key].decode` | identity | Optional guard over the server-validated JSON object; the return value sets the key's type. |
| `endpoint` | `/_web-config/v1/stream` | Pass only when the starter overrides `path`. |
| `credentials` | `'same-origin'` | Set when the stream sits behind an authenticated cross-origin host. |
| `fetch` | `globalThis.fetch` | Injectable for tests. |
| `onError` | none | Receives every `WebConfigError`. Without it, a rejected decoder still surfaces as one `console.warn` per key. |

### The client

| Member | Behavior |
| --- | --- |
| `get(key)` | Current `ConfigState`, read-only, no network. Throws on an unknown key. |
| `subscribe(key, listener)` | Returns an unsubscribe function. Throws `CLIENT_CLOSED` after `close()`. |
| `close()` | Aborts the stream, clears timers, drops all listeners. Idempotent. |

### `ConfigState`

| Field | Meaning |
| --- | --- |
| `value` | Decoded config, deep-frozen — mutating it in a page cannot corrupt the cache. |
| `source` | `'remote'` \| `'fallback'` \| `'none'`. |
| `status` | `'loading'` \| `'ready'` \| `'invalid'` \| `'deleted'` \| `'unavailable'`. |
| `stale` | `true` while the value is retained from an earlier snapshot rather than the live source. |
| `errorCode` | Present on failures, e.g. `DECODER_ERROR`, `SOURCE_UNAVAILABLE`, `PROTOCOL_ERROR`. |

Outage and deletion are distinct on purpose: a Nacos outage marks the key `unavailable`/`stale` and
keeps the last known good value, while an operator deletion marks it `deleted` and falls back.

## Failure handling built in

- Reconnect uses randomized exponential backoff capped at 30 s, and honors `Retry-After` on `429`.
- A stream that goes 45 s without bytes is aborted and re-opened; a hidden tab that becomes visible
  again refreshes immediately instead of waiting out the backoff.
- Sequence numbers are checked: a snapshot is always `seq 0`, changes must arrive in order, and an
  out-of-order or unknown event is reported as `PROTOCOL_ERROR` rather than applied.
- `400`/`401`/`403`/`405`/`406` are reported through `onError` as fatal — no retry storm against a
  server that is refusing you.

## Documentation

- [Project README](https://github.com/FatMii/nacos-web-config#readme) ·
  [中文](https://github.com/FatMii/nacos-web-config/blob/main/README.zh-CN.md)
- [Changelog](https://github.com/FatMii/nacos-web-config/blob/main/CHANGELOG.md) ·
  [Security policy](https://github.com/FatMii/nacos-web-config/blob/main/SECURITY.md)

## License

Apache License 2.0 — see [LICENSE](./LICENSE) and the third-party notices in
[NOTICE](./NOTICE).
