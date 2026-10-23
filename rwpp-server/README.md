# RWPP Server

Rusted Warfare 的 JVM 无头服务端，提供控制台和扩展加载。
与客户端共享 [rwpp-api](../rwpp-api)，不依赖声音、OpenGL 或 UI 库。
同端口提供 KCP/UDP 与 TCP，RWPP 客户端优先 KCP，连接失败后回退 TCP。
Netty 仅由 server 引入，负责批量收发、背压及共享线程池；KCP 核心与客户端共用 API 实现。

## 构建

需要 Java 25 和游戏资源。

```powershell
.\gradlew.bat :rwpp-server:installDist
```

发行目录：`build/install/rwpp-server/`；压缩包使用 `:rwpp-server:distZip` 构建。

## 启动

运行目录需包含原版游戏的 `assets/`，扩展放入 `extensions/`。

```powershell
.\scripts\start-server.ps1 -RuntimeDir 'E:\server-runtime' -Port 5123
```

也可在运行目录调用发行包的 `bin/rwpp-server.bat`，Linux 使用 `bin/rwpp-server`。

| 选项 | 用途 |
|------|------|
| `--help` | 查看启动参数 |
| `--port 5123` | TCP / UDP 端口，默认 5123 |
| `--no-console` | 后台运行 |
| `--ticks 180` | 运行指定帧数后退出 |
| `--transfer-mods` | 允许下载服务端启用的模组 |
| `-- <参数>` | 向服务端扩展传参 |

控制台支持 `help`、`status`、`map`、`start`、`say`、`stop`。

## 扩展

扩展使用 `compileOnly("io.github.rwpp-team:rwpp-server:2.0.0")`，
描述符声明 `dependsOn=io.github.rwpp.api,io.github.rwpp.server`。
普通扩展遵循 RWML 生命周期；实现 `ServerExtension` 可接管运行循环。

AOI-Veil 的 server 与 API provider 两个 JAR 放入 `extensions/`，在运行目录启动：

```powershell
E:\rwpp-server\bin\rwpp-server.bat -- --config server.properties
```

模块职责见 [项目结构](../docs/PROJECT-STRUCTURE.md)，实机验证见 [验证记录](VALIDATION.md)。
吞吐对照与适用范围见 [性能报告](../docs/NETWORK-PERFORMANCE.md)。
