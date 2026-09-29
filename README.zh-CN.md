<h1 align="center">Nacos Web Config</h1>

<p align="center">
  <em>让网页安全地拿到 Nacos 里指定的 JSON 配置，配置一改，页面跟着更新。</em>
</p>

<p align="center"><a href="./README.md">English</a></p>

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

> **0.1.0** 已在 Maven Central 与 npm 发布。下面的快速开始从公共仓库安装。

```text
+-------+   watch    +---------------------+   HTTP/SSE  +-------------+            +------+
| Nacos | ---------> | Spring Boot Starter | ----------> | Browser SDK | -------->  | Page |
+-------+            +---------------------+ same-origin +-------------+            +------+
```

---

# 目录

- [它是什么](#它是什么)
- [为什么需要它](#为什么需要它)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [本地演示](#本地演示)
- [配置参考](#配置参考)
- [兼容性](#兼容性)
- [局限](#局限)
- [安全](#安全)
- [许可证](#许可证)

---

# 它是什么

- 装进现有 Spring Boot 应用的 **starter**（不用再起一个服务）
- 一个很小的**前端 SDK**（TypeScript ESM，没有运行时依赖）
- 一条**只读**通道：网页只能拿到你事先允许的那些 JSON 配置

---

# 为什么需要它

页面上的公告、功能开关、活动文案，经常存在 Nacos 里。运营一点发布，就希望线上页面跟着变，而不是等下一次前端发版。

但网页不该自己去连 Nacos：

- **账号不能写进前端。** 开发者工具里谁都能看到。拿到 Nacos 账号的人不只会读，还能改、删。
- **没法只放开一条配置。** Nacos 按命名空间授权。你没法说：网页只能看这一条，同命名空间别的一律不准碰。
- **直接对外很危险。** 谁都能打的话，配置中心容易被刷挂；一挂，依赖它的后端都会受影响。

所以还是让你的 Spring Boot 去连 Nacos，网页只连自己的后端。你在配置里写清楚哪些名字可以给网页看，后端校验后再推过去。

| 场景 | 自己写定时接口 | nacos-web-config |
| --- | --- | --- |
| 谁能看到什么 | 鉴权、映射都自己写 | **只放出你点名的那些名字** |
| 运营推了坏内容 | 规则自己定，页面容易直接挂 | **丢掉坏数据，继续用上一份好的** |
| 发布后要尽快出现在页面 | 继续轮询，或自己做推送 | **后端直接推变更** |
| Nacos 临时挂了 | 自己约定怎么表现 | **不把页面上的值清掉** |
| 接入成本 | 缓存、协议、测试都自己扛 | **依赖 + 一段 YAML + `subscribe()`** |

### 什么时候不用这个库

如果只是一条公告，不追求实时变更：后端开一个普通接口，前端定时请求就够了。

---

# 核心特性

## 只能看到你允许的配置

在 YAML 的 `exposures` 里写明：网页用的名字（比如 `ui`）对应 Nacos 里哪一条配置。前端只按这个名字订阅，看不到 Nacos 的地址、group、dataId。

还必须写明谁能连：`public`（谁都能读这些允许的配置）或 `authenticated`（只有已登录用户）。不写清楚，应用直接启动失败。

## 坏数据不会弄挂页面

推过来的内容必须是 JSON 对象，并且不能超过你设的大小（`max-bytes`）。不合格的内容会被丢掉，页面继续用上一份正常数据。内容完全没变时，也不会重复推。

## 发布后直接推到页面

后端用 SSE 推送变更，不用前端定时拉取。一条连接最多同时订 32 个名字；先发完整快照，再发之后的改动。顺序乱了前端会拒绝，然后重新拉一份完整数据，避免页面状态错乱。

## 断线、故障也能撑住

Nacos 暂时连不上时，不会把页面上的值清掉，只是标成过期，通了再自动读回来。前端断线会自动重试；标签页从后台切回来时，也会马上刷新。某个浏览器特别慢时，只影响它自己，不会拖垮其他人。

---

# 快速开始

### 前置条件

- JDK 17+、Maven、Node.js（CI 固定 Temurin 17 / Node 22）
- Spring Boot 3.x **Servlet/MVC** 应用
- Nacos Server 2.5.3（3.2.3 仅 Adapter 实测，见[兼容性](#兼容性)）

### 1. 后端：引入 starter 并声明白名单

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
  enabled: true             # 默认关闭，须显式启用
  access: public            # 必填：public 或 authenticated
  nacos:
    server-addr: 127.0.0.1:8848
  exposures:                # 白名单：逻辑 key -> 固定 Nacos 坐标
    ui:
      group: WEB_DEMO
      data-id: web-demo.public.json
      max-bytes: 65536
```

启动后暴露 SSE 端点 `/_web-config/v1/stream`（可用 `path` 修改）。

**`access` 怎么选**

- **`public`**：能访问端点的人都能读白名单配置（只读，读不到整库）。适合陌生人可见的公告。
- **`authenticated`**：请求上已有登录身份才允许接流。Starter 只检查 `request.getUserPrincipal()` 是否非空，不问身份由谁挂上（Spring Security、自写 JWT/session 过滤器、容器认证均可），自己从不登录。
- 应用里若没有任何组件设置身份，一律 401（故意关死）。
- 级别是整条流一个，不能逐键设置。

### 2. 前端：订阅

```bash
npm install @fatmii/nacos-web-config
```

```ts
import { createWebConfig } from '@fatmii/nacos-web-config'

const config = createWebConfig({
  // endpoint 可省略（默认 '/_web-config/v1/stream'）；decode 可省略
  definitions: {
    ui: { fallback: { announcement: '' } },
  },
})

const unsubscribe = config.subscribe('ui', state => {
  renderAnnouncement(state.value?.announcement ?? '')
})
// config.get('ui') 只读当前状态，不发网络请求；首次 subscribe() 才建立共享连接。
```

页面要求更严时，给键加 `decode`：不合格值被拒收，保持最近有效值，键标为 `invalid`，经 `onError` 上报（未提供时每个键 `console.warn` 一次）：

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

# 本地演示

需要本机已有 **JDK 17、Maven**，以及能跑 `linux/amd64` 镜像的 **Docker**（Docker Desktop / OrbStack 等）。`dev-environment/start.ps1` 面向维护者本机（固定 `desktop-linux` 上下文），**不保证**在每台机器上都能直接跑通；更稳妥的做法是只用 Compose 起 Nacos 2.5.3：

```powershell
cd dev-environment
docker compose --project-name nacos-web-config-local up -d nacos2
# 等控制台可打开：http://127.0.0.1:18848/nacos
```

再启动参考宿主（注意端口是 `18848`，不是默认的 `8848`）：

```powershell
cd ..\java
$env:NACOS_SERVER_ADDR='127.0.0.1:18848'
mvn -pl examples/spring-mvc -am spring-boot:run
```

打开 `http://127.0.0.1:8080/`，在 Nacos 控制台的 `WEB_DEMO` 组发布 `web-demo.public.json`（例如 `{"announcement":"hello"}`）。页面应在数秒内更新，无需刷新。

更细的说明见 [`java/examples/spring-mvc/README.md`](./java/examples/spring-mvc/README.md) 与 [`dev-environment/README.md`](./dev-environment/README.md)。

---

# 配置参考

所有项都在 `nacos-web-config` 前缀下。非法值会让应用**启动失败并给出精确报错**，不会悄悄给默认值。下面是一份带默认值的完整配置（`//` 仅作说明，真正 YAML 请改用 `#`）：

```yaml
nacos-web-config:
  enabled: false                    // 总开关；不写 true 模块不工作
  access: public                    // 必填：public | authenticated，见快速开始「access 怎么选」
  path: /_web-config                // 端点前缀，推送流为 <path>/v1/stream；须 / 开头、不能以 / 结尾

  exposures:                        // 白名单，至少一条；别名规则 ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$
    ui:                             // 前端订阅用的逻辑名，可按需改名或增删
      data-id: web.ui.json          // 必填：这条配置对应的 Nacos dataId
      group: DEFAULT_GROUP          // 对应的 Nacos group
      max-bytes: 65536              // 单条上限；超限或非 JSON 对象会被丢掉，页面继续用上一份好的

  nacos:
    server-addr: 127.0.0.1:8848     // starter 自建客户端时必填，host:port，可逗号分隔多个；复用现有客户端时可省略
    namespace: public               // 本实例服务的唯一 namespace
    username: ""                    // Nacos 开启鉴权时填写；凭据只留在后端
    password: ""
    timeout: 3s                     // Nacos 客户端操作超时

  source:
    mode: auto                      // auto：恰有一个 ConfigService bean 就复用，否则用 nacos.* 自建（多个 bean 时报错）；bean：复用 bean-name；managed：总是自建
    bean-name: ""                   // mode=bean 时必填，要复用的 ConfigService bean 名

  stream:                           // 默认面向小中型流量，大规模再调
    heartbeat: 15s                  // SSE 心跳间隔
    connection-timeout: 30m         // 服务端到点关流；SDK 会自动重连并再取快照
    max-connections: 1000           // 同时在线流上限；超出回 429
    max-keys-per-connection: 32     // 单连接最多订阅几个逻辑名
    max-pending-bytes-per-connection: 1MB  // 慢客户端积压预算；超限只掐断该连接

  startup:
    fail-fast: false                // true：启动时拿不到白名单配置则失败；默认应用照常起，页面先用兜底/旧值
```

### 键怎么划分：优先聚合键

一个逻辑键对应一条固定 Nacos 配置；新增键要改 `application.yml` 并重启，对外可见范围每次扩大都留部署痕迹。高频改动的内容不必新键：暴露一个粗粒度键（如 `homepage`），字段放进那条 JSON，运营改一条即时生效。只在真实边界拆键：访问级别、负责人、大小预算不同。

---

# 兼容性

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17+ | Spring Boot 3.x，Servlet/MVC |
| Nacos Client | 2.5.3 | 唯一支持的客户端线 |
| Nacos Server | 2.5.3 | 含浏览器完整链路实测（Java 测试 + browser E2E） |
| Nacos Server | 3.2.3 | 仅 Java Adapter 集成测试，不宣称完整浏览器链路 |
| 浏览器 | 现代浏览器 | 需 `fetch` + `ReadableStream`，无 EventSource 回退 |

---

# 局限

- Nacos 3.2.3 仅 Adapter 级兼容。
- 仅 Servlet MVC；无 WebFlux 变体。
- 值必须是 `max-bytes` 以内的 JSON 对象。
- SDK 仅 ESM；老浏览器需自备构建/兼容方案。
- 无管理界面；创建与编辑仍在 Nacos 控制台。

---

# 安全

漏洞报告与产品安全模型见 [SECURITY.md](SECURITY.md)。请勿用公开 Issue 报告安全问题。

---

# 许可证

[Apache License 2.0](./LICENSE)。第三方依赖声明见 [NOTICE](./NOTICE)。
