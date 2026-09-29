# Changelog

本文件记录 Nacos Web Config 的所有重要变更。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

`0.x` 阶段约定：次版本号（minor）变化允许破坏性 API 变更；补丁号只做修复。破坏性变化会在本文件中标注 **BREAKING**。

## 0.1.0 — 2026-09-28

### Added

- `nacos-web-config-server-core`：白名单注册表、JSON 对象校验与字节上限、LKG（last-known-good）状态、快照与逐订阅者串行投递。
- `nacos-web-config-nacos-adapter`：Nacos ConfigService 监听、删除识别、故障检测与恢复；Client 2.5.3 线对 Server 2.5.3 / 3.2.3 实测通过。
- `nacos-web-config-spring-mvc-adapter`：HTTP/SSE 流端点，snapshot/change 协议（protocol=1、streamId、单调 seq）、心跳、连接数限额（429 + Retry-After）、慢客户端字节配额隔离、稳定错误协议。
- `nacos-web-config-spring-boot-autoconfigure` / `-starter` / `-starter-test`：YAML 属性绑定与启动期校验；`access` 必须显式选择 `public` 或 `authenticated`；不连真实 Nacos 的宿主测试工具。
- `@fatmii/nacos-web-config` Browser SDK（TypeScript ESM）：懒连接、`get`/`subscribe`/`close`、fallback、严格协议校验（乱序拒收、streamId 一致性）、指数退避重连、429 Retry-After、页面恢复与活性超时。
- 内容拒收可观测性：`ConfigRuntime` 新增 `ConfigRejectionListener`；自动配置与 starter-test 均经 `startWithRejectionLogging` 启动，每次拒收打一条 WARN（逻辑键、稳定错误码、被拒内容 sha256；不含原文）。行为测试 60→62。
- Apache-2.0 许可证（`LICENSE` + `NOTICE`）；Maven 与 npm 发布元数据；`publishing` profile（源码包、文档包、GPG 签名、Central 发布插件，`autoPublish=false`）。

### Changed

- Browser SDK 易用性：`decode` 改为可选（省略时服务端已校验的 JSON 对象冻结后原样透传，`value` 类型为 `unknown`，或由 `fallback` 推断）；`endpoint` 改为可选（默认 `/_web-config/v1/stream`，与 Starter 默认 `path` 对齐）。快速开始收敛为 4 行。
- Browser SDK：decoder 拒收且未提供 `onError` 时，每键输出一次 `console.warn`，消除静默失败。行为测试 16→19 项（透传、默认 endpoint、告警去重）。
- Spring Boot 依赖线 3.5.6 → 3.5.16、Jackson 2.18.2 → 2.18.11，清除首轮 Trivy 扫描报告的大部分 HIGH/CRITICAL 漏洞来源；nacos-client 保持 2.5.3 依赖线不变。
- 父 POM 将 tomcat-embed 强制至 10.1.60（Boot 3.5.16 管理的 10.1.55 含 3 个 CRITICAL 认证/安全约束绕过漏洞，10.1.58 已修复；Boot 跟进后可移除该覆盖）。
- CI 安全扫描由只报告改为不合格即阻断（`SCAN_EXIT_CODE: '1'`）。
- 放宽 `spring-mvc-adapter` 中依赖调度的 HTTP 测试时间预算，并把慢客户端字节配额测试的阈值与事件大小拉开差距，使构建机负载不再造成假性失败。

### Known limitations

- Nacos 3.2.3 仅声明 Adapter 实测兼容，完整 Browser 链路证据受 Admin token 条件限制，暂不宣称完整支持。
- 暴露键（`exposures`）在启动期固定，新增键需改配置并重启应用；免重启的动态白名单未实现。

