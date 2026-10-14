# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 记变更，版本号跟 [SemVer](https://semver.org/lang/zh-CN/)。

英文版：[CHANGELOG.md](./CHANGELOG.md)

`0.x`：minor 可以破坏兼容；patch 只修问题。破坏性变更会标 **BREAKING**。

## 0.2.0 — 2026-10-14

### Added

- 可选的运维面（宿主在用 Spring Boot Actuator 时自动出现）：`nacosWebConfig` 健康组件（连不连得上 Nacos，以及每个键在服务什么状态）+ 三个指标：`nacos.webconfig.stream.connections`、`nacos.webconfig.stream.events`（按帧类型）、`nacos.webconfig.rejections`（按原因码，不带内容）。Actuator 仍是可选依赖：没装的应用一点变化都没有，照常启动。
- 为它新增的只读接缝：`ConfigSource.available()`、`ConfigRuntime.aliases()`、`ConfigRuntime.sourceAvailable()`，以及 SSE 引擎的只读视图 `WebConfigStreamStatus`。
- `java/examples/spring-mvc-secured`：可运行的 `access: authenticated` 宿主。未登录的 `Accept: text/event-stream` 请求拿 `401`，普通页面请求仍走登录表单。
- 演示页改用已发布的 `@fatmii/nacos-web-config` SDK，不再是手写 `EventSource`；示例里那份副本与 SDK 构建产物一旦不一致，`verify.yml` 直接失败。
- `.github/workflows/e2e-nacos.yml`：对真实 Nacos **2.5.3** 与 **3.2.3** 跑浏览器端到端，覆盖三条路径 —— 正常推送、宿主重启后重连、经 Nginx 代理不缓冲。夜间、手动、推送时都会跑。
- `dev-environment/scripts/fetch-nacos3-admin-token.mjs`：为这些用例拿 Nacos 3 Admin 短期 token，含首次启动时的管理员初始化。
- `.github/workflows/publish-github-packages.yml`：Maven 与 npm 制品同时发布到 GitHub Packages。
- 一批"实测再写"的文档：网关与反向代理（哪些设置真会咬人、哪些我们复现不出问题所以不写成必配）、前端本地开发代理与 React/Vue 接法、中文内容与 Windows 发布坑、"最终装进你应用的版本谁说了算"对照表，以及 `SECURITY.md` 里 SSE 那条豁免的适用边界。
- 官网：交互流程大图、真实推送录屏（替换原先的模拟演示）、中英双语 changelog。

### Changed

- 兼容性：Nacos Server 3.2.3 的完整浏览器链路改为"已声称支持"，依据就是上面那条 E2E。
- Jackson 从 2.18.11 提前钉到 2.21.7，以清掉 Spring Boot 3.5 这条线上的 HIGH/CRITICAL 门。CVE-2026-47890 则改为有记录的豁免：`spring-webmvc` 6.2.x 没有开源补丁版，本库只经 SSE 推 JSON 载荷，并且 `WebConfigNoViewFragmentOutputTest` 会在视图对象进入该路径时直接让构建失败。
- GitHub Pages 部署改为必须等 Verify 通过。

### Fixed

- 普通 SSE 客户端断开 / broken pipe 不再走 `completeWithError`；连接安静结束。若宿主用 Logback，还会过滤 Tomcat/`dispatcherServlet` 对这类良性断开打的 ERROR（本模块只打 DEBUG）。
- `examples/spring-mvc` 现在能打出可执行 jar（`repackage` 目标绑成了 execution），E2E 跑的就是它。
- 两条"可能因为错误原因变绿或变红"的端到端断言改成真测其声称内容：代理用例显式传心跳间隔，解码用例断言错误码而不是投递次数。

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

- 白名单里的键在启动时定死；要加键就改配置并重启。运行中改白名单还做不到。
