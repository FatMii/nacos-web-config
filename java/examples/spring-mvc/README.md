# Spring MVC 参考宿主

最小 Spring Boot MVC 应用：加上 Starter 依赖和 YAML 后，就能拿到 `/_web-config/v1/stream`，不用自己写 Nacos 监听、缓存或 SSE Controller。

应用里的 Java 只有启动入口和 `GET /demo`。根页面 `/` 订阅公开别名 `ui`，看不到真实的 Nacos group/dataId。

## 本地启动

先在 Nacos 的 `WEB_DEMO` group 中创建 `web-demo.public.json`：

```json
{"banner":{"enabled":true,"text":"hello"},"refreshIntervalMs":30000}
```

然后从 `java/` 目录运行：

```powershell
$env:NACOS_SERVER_ADDR='127.0.0.1:8848'
mvn -pl examples/spring-mvc -am spring-boot:run
```

打开 `http://127.0.0.1:8080/`。凭据仅通过 `NACOS_USERNAME`、`NACOS_PASSWORD` 环境变量提供，不写入仓库。

公共命名空间的 ID 是空字符串，因此 `NACOS_NAMESPACE` 默认留空；只有使用自定义命名空间时才设置其真实 namespace ID。

测试使用 `spring-boot-starter-test` 制品替换真实 Nacos，但仍穿过完整 core、MVC、HTTP 和 SSE 链路：

```powershell
mvn -pl examples/spring-mvc -am test
```

## 端到端测试

先构建 Browser SDK、启动本应用并连接测试 Nacos，然后从仓库 `browser/` 目录显式运行：

```powershell
$env:NWC_E2E_HOST='http://127.0.0.1:8080'
$env:NWC_E2E_NACOS='http://127.0.0.1:18848'
npm run test:e2e
```

要让同一条链路同时经过真实 Chrome，而不只是 Node.js 中的 Browser SDK，可额外提供本机 Chrome 或 Edge 可执行文件并运行浏览器验收命令：

```powershell
$env:NWC_E2E_BROWSER_EXECUTABLE='C:\Program Files\Google\Chrome\Application\chrome.exe'
npm run test:e2e:browser
```

测试会把验收页和已经构建的 Browser SDK ESM 产物挂到参考宿主的同源地址，浏览器再从真实 Starter SSE 端点读取配置。它只启动临时的无界面浏览器实例，不下载浏览器，也不会把 Browser SDK 复制进 Java 制品。

Nacos 3.x 的宿主仍连接客户端端口 `28848`，但测试发布端也使用该端口的新 Admin API，并额外设置：

```powershell
$env:NWC_E2E_NACOS='http://127.0.0.1:28848'
$env:NWC_E2E_NACOS_API='v3'
$env:NWC_E2E_NACOS_TOKEN='<从管理员登录安全取得的短期 token>'
```

Nacos 3 Admin API 默认强制鉴权；测试只读取环境变量中的短期 token，不接收或记录管理员密码。不要把 token 写入仓库或命令历史。

测试会通过 Nacos API 发布初始值和更新值，验证 Browser SDK 经 Starter 收到 ready；重复发布同一内容时验证不产生重复业务回调，连续发布 A/B/C 时验证最终稳定为 C。随后先发布“JSON 合法但业务字段类型错误”的内容，验证 Browser decoder 报告 `DECODER_ERROR` 并保留 LKG；再发布非法 JSON，验证服务端 `INVALID_JSON` 同样保留 LKG；删除配置后还会再次发布非法内容，确认已删除的旧值不会复活，最终仍使用 fallback。结束时再次清理测试配置。它不会随普通单元测试自动运行。

若要额外验证 Nacos 整体停机与恢复，可指定一个专门用于测试的 Docker 容器：

```powershell
$env:NWC_E2E_DOCKER_CONTAINER='nacos-web-config-local-nacos2-1'
npm run test:e2e
```

此模式会真实停止该容器，确认 Browser SDK 进入 unavailable/stale 且继续保留最后正确配置；随后重新启动容器、等待 Starter 与 Nacos 重连，并验证新配置恢复为 ready。测试清理钩子会在异常退出时尽力重新启动该容器，因此这里只能填写可安全停启的测试实例，不能填写生产容器。

若要验证另一种常见故障——Nacos 正常运行，但承载 Starter 的 Java 应用发生滚动重启——先构建可执行宿主包，再让夹具管理这个临时 Java 进程：

```powershell
# 在 java/ 目录构建可执行包
mvn -pl examples/spring-mvc -am package spring-boot:repackage -DskipTests

# 在 browser/ 目录运行；JAVA 路径按本机安装位置填写
$env:NWC_E2E_HOST_JAR='D:\workspace\github\nacos-web-config\java\examples\spring-mvc\target\nacos-web-config-example-spring-mvc-0.1.0.jar'
$env:NWC_E2E_JAVA='C:\path\to\jdk-17\bin\java.exe'
$env:NWC_E2E_RESTART_ONLY='true'
$env:NWC_E2E_BROWSER_EXECUTABLE='C:\Program Files\Google\Chrome\Application\chrome.exe'
npm run test:e2e:restart
```

夹具会启动宿主，让 Node 与 Chrome 中的正式 Browser SDK 取得初始配置；然后终止整个宿主进程树，在停机期间向 Nacos 发布新值，再启动全新的宿主。验收成功意味着浏览器把网络断流当作可恢复故障、保留最后正确配置并重连，最终接受新宿主发出的完整权威快照。该模式会真实终止它自己启动的 Java 进程，因此不要把 `NWC_E2E_HOST_JAR` 指向正在人工使用的服务。

若要验证 Nginx 反向代理不会缓冲 SSE，先按 `dev-environment/README.md` 启动 `proxy` profile，然后在 `browser/` 目录运行：

```powershell
$env:NWC_E2E_HOST='http://127.0.0.1:18081'
$env:NWC_E2E_HOST_JAR='D:\workspace\github\nacos-web-config\java\examples\spring-mvc\target\nacos-web-config-example-spring-mvc-0.1.0.jar'
$env:NWC_E2E_JAVA='C:\path\to\jdk-17\bin\java.exe'
$env:NWC_E2E_PROXY_ONLY='true'
$env:NWC_E2E_BROWSER_EXECUTABLE='C:\Program Files\Google\Chrome\Application\chrome.exe'
$env:NACOS_WEB_CONFIG_STREAM_HEARTBEAT='1s'
npm run test:e2e:proxy
```

该验收同时检查代理保留 `X-Accel-Buffering: no`、首个 snapshot 不被攒包、心跳按配置间隔到达，以及更新能及时传到 Node SDK 和真实 Chrome。
