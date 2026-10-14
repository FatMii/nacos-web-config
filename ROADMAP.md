# ROADMAP

Short version of what we are building next. It is a plan, not a contract: items move when a maintainer
or enough users push them, and anything here can be dropped.

Questions and proposals go to [issues](https://github.com/FatMii/nacos-web-config/issues) with the
`question` label until Discussions is enabled here; concrete bugs and requests use the forms below.
The rule that decides everything on this page is the one the README states: a browser may read a
whitelist of Nacos JSON keys, and nothing else.

## In 0.2.0 (merged, not published yet)

- Operational surface, optional: a `nacosWebConfig` health component and `nacos.webconfig.*` metrics
  for open streams, pushed frames and refused publishes. Hosts without Actuator are untouched.
- A runnable `access: authenticated` example, and the demo page driven by the published SDK.
- Browser end-to-end against real Nacos 2.5.3 and 3.2.3, including reconnect after a host restart and
  delivery through an Nginx proxy.
- Measured integration docs: gateways, front-end dev-server proxies, React and Vue wiring, Chinese
  content on Windows, and who owns dependency versions in your application.

## Next (0.3 candidates)

- Reap the connection gauge the moment an async request times out, so `stream.connections` stops
  counting a vanished client without waiting for the next write to fail.
- Dev-server proxy guidance for tools other than Vite, measured the same way (webpack-dev-server
  first), because "does it buffer my stream" has a different answer per tool.
- A first-class Spring Boot property for the operator log: choose the logger name and level instead
  of relying on `io.github.fatmii.nacoswebconfig.REJECTIONS`.
- SDK: surface reconnect backoff and stream health through the existing state object, so a page can
  show "falling back to polling" without reading internals.

## Later, maybe

- Multiple Nacos namespaces behind one host, with an explicit per-namespace stream. Today one runtime
  instance serves one namespace, and adding a second instance is the supported answer.
- Per-key read policies (different keys for different roles) once we have a request model that does
  not leak the whitelist shape to the browser.

## Not planned

We will not build these, and a PR adding one is likely to be closed rather than argued about:

- An admin UI. Configuration is authored in the Nacos console; this project streams it read-only.
- Changing the `exposures` whitelist at runtime. The allowlist is fixed at startup on purpose, so
  every widening of the public surface leaves a deploy trail.
- WebFlux, or any reactive server. Servlet MVC only.
- Turning the payload into anything other than a JSON object, or raising the size cap on behalf of a
  single page.
- A feature-flag or experimentation engine. Keys are configuration, not targeting.
