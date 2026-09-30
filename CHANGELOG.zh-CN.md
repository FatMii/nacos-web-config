# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 记变更，版本号跟 [SemVer](https://semver.org/lang/zh-CN/)。

英文版：[CHANGELOG.md](./CHANGELOG.md)

`0.x`：minor 可以破坏兼容；patch 只修问题。破坏性变更会标 **BREAKING**。

## 0.1.0 — 2026-09-28

### Added

- 后端核心：白名单、JSON 对象校验、大小上限、坏数据丢掉并保留上一份好的、快照和推送。
- Nacos 适配：监听配置、识别删除、连不上时能恢复。客户端固定 2.5.3；在 Server 2.5.3 / 3.2.3 上跑过适配层测试。
- Spring MVC：同源 HTTP/SSE（先快照再推变更）、心跳、连接数上限（超了回 429）、慢客户端只掐自己那条连接。
- Spring Boot 自动配置 / starter / starter-test：读 YAML；`access` 必须写成 `public` 或 `authenticated`；单测可以不连真 Nacos。
- 浏览器 SDK `@fatmii/nacos-web-config`：订阅、取值、断线重连；序号乱了就拒收再拉快照。
- 内容被拒时打一条 WARN（键名、错误码、内容哈希，不打原文）。
- Apache-2.0；已发到 Maven Central 和 npm。

### Changed

- SDK：`decode`、`endpoint` 都可以不写（endpoint 默认 `/_web-config/v1/stream`）。
- decode 失败又没传 `onError` 时，每个键最多 `console.warn` 一次，避免静默失败。
- Spring Boot 升到 3.5.16，Jackson 到 2.18.11；tomcat-embed 钉到 10.1.60。
- CI 扫到 HIGH/CRITICAL 会直接失败。
- 几处计时敏感的 HTTP 测试加大了超时余量，减少忙碌机器上的误报。

### Known limitations

- Nacos 3.2.3：适配层测过了。完整浏览器链路还没在 3.2.3 上跑通（拿 Admin token 不方便），所以文档不写「完整支持」。
- 白名单里的键在启动时定死；要加键就改配置并重启。运行中改白名单还做不到。
