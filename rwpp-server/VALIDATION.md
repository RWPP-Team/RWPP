# 2026-10-24 RWPP Server 扩展验证

## 当前：服务端收发优化

Netty 仍仅在 server，默认 4 个 I/O / 4 个有序报文线程。普通小包快速入队，系统包及大包走工作线程；
合并发送、分段大帧和读后 flush 保留快照、顺序、原生发送延迟与队列上限。断线通知移到锁外，修复连接锁反转。

- API 11 项、server 52 项测试通过；新增覆盖并发分配、延迟、快速回调边界、失败通知、部分分配失败与大帧释放。
- API / client / server 依赖检查、客户端网络归档与 JVM 扩展、server 发行包和 Maven Local 发布通过。
- 原生 Slick/LibRocket 客户端分别通过 KCP、自动回退 TCP、vanilla TCP，均到 tick 301、移动约 117 像素并正常退出。
- AOI 客户端发行归档检查 3 项通过，server 扩展包已重建；实机切图、AI 开局、寻路、兴趣过滤通过：742 次组提交、
  99 次摘要匹配、0 次偏离、3 次原子恢复、移动 137.11 像素，tick 单调、隐藏敌军未下发，宿主退出码 0。

证据：`build/optimized-network-current-verified-build.log`、`build/optimized-current-native-cases.log`、
`build/optimized-current-aoi-native.log`；
AOI 构建日志为 `E:/AOI-Veil/build/optimized-network-current-extension-build.log`。
10 月 23 日的 30 轮对照全部收齐且零错误；10 月 24 日 API 其他修改后重新构建、复验，157 个网络类与压测版本字节码一致。
重复吞吐对照和限制见 [性能报告](../docs/NETWORK-PERFORMANCE.md)。Android 与旧客户端测试的历史限制仍见下文。

## 前一轮：Netty 仅服务端（历史）

API 共用纯 Kotlin KCP 核心与报文契约；client 的轻量网络归档使用 JDK，Netty 仅由 server 依赖。
服务端使用共享 I/O / 有序报文池，批量解析、提交和 flush，并以有界队列和读写水位控制积压。

- API 与 client 依赖检查通过；客户端网络归档约 36 KiB，无 Netty / Java-KCP。
- API 11 项、server 30 项测试通过，已覆盖标准 KCP 与旧 Java-KCP 双向互通、丢包 / 重排 / 分片、客户端同 transport 重连，以及
  60 KiB TCP 下行。
- 本轮原生桌面客户端分别通过 KCP、自动回退 TCP、vanilla TCP，均到 tick 301，移动约 117 像素，正常退出。
- AOI 作为 rwpp-server 扩展，真实客户端通过 KCP 入服、切图、AI 开局、寻路与兴趣过滤：754 次组提交、
  102 次摘要匹配、0 次偏离、3 次原子恢复，移动 137.11 像素，tick 单调且隐藏敌军未下发。
  失效旧凭据后的 TCP 回退及重新入服也通过（754 次提交、102 次匹配、0 次偏离）。

构建和本机证据：`build/server-only-network-final-build.log`、`build/server-only-native-cases-final.log`、
`build/server-only-aoi-native-final.log`、`build/server-only-aoi-native-diagnostic.log`。
高吞吐对照的方法与结果见 [网络性能报告](../docs/NETWORK-PERFORMANCE.md)。
36 轮主对照与 18 轮 I/O 线程配对全部完成，回显收齐且错误为零。TCP 饱和小帧为原版模型 3.27 倍；
多 KiB 帧从 2 增至 4 个 I/O 线程后配对吞吐提升 39.7%–48.8%，CPU 与部分 p99 也更高，不宣称普遍加速。
Android / 旧客户端测试限制见历史记录，不以本机连通性推算公网改善。

## 前一轮：KCP 优先及 TCP 回退（历史）

客户端先验证 KCP welcome，最多等待 1.5 秒；失败后关闭 UDP 尝试，在剩余预算内连接 TCP。
服务端同端口监听 KCP 和 TCP；原版客户端可走 TCP，旧 RUDP 仅在 `rwpp.network.mode=vanilla` 启用。
KCP 使用有界分块、有界收发队列及事件触发调度，保留游戏原生报文与处理顺序。

- API 13 项、server 7 项测试通过，含真实 UDP、KCP 握手/回退、零长度与 2 MB 帧、关闭尾包、早断回调、TCP 拆包/合包及 Mixin 变换。
- 客户端 JVM 扩展、server 检查、发行包和本地 Maven 发布通过；构建日志 `build/kcp-final-validation.log`。
- 原生 Slick/LibRocket 客户端分别通过 KCP、自动回退 TCP、vanilla TCP 对照。三者均到 tick 301、移动 116.80 像素，
  无摘要偏离，控制台正常退出。驱动：`../scripts/test-native-network.py`；结果：`build/kcp-native-cases.log`。
- AOI 作为 rwpp-server 扩展完成真实客户端加入、切图、AI 开局、寻路和兴趣过滤：755 次组提交、102 次摘要匹配、
  0 次偏离、3 次原子恢复；移动 137.11 像素、tick 单调、隐藏敌军未下发。日志：`build/kcp-aoi-native.log`。

性能对照完成 5 种模式、5 个场景、各 3 轮，共 75 轮：KCP / 两种 TCP 的 45 轮全部收齐，
RUDP 的 6 次大包超时保留。方法、全部样本和限制见 [网络性能报告](../docs/NETWORK-PERFORMANCE.md)。
本机 ping 0–2 ms 只作为连通证据，不作为延迟优化比例。Android 与旧客户端测试的限制仍见下文。

## 前一轮：原生 TCP 与共享模组协议（历史）

Protobuf 依赖及序列化入口已移除。ModPacket、编解码、模组请求与完成通知由 API 提供，
客户端与服务端共用；独立服务端以 `--transfer-mods` 启用分发。

原版 TCP 两端均由 API 的 Netty hook 接管，保留原生报文与游戏线程语义，原版 RUDP 保留兼容。
修复了 Connection 在 hook 注册前被加载的问题，以及房间列表误用本地队伍判断的问题。

- API 9 项、服务端 7 项测试通过，覆盖原生报文拆包/合包、2 MB 模组帧、超限头部、编解码和实际 Mixin 变换。
- 客户端 JVM 编译、扩展打包、服务端检查、发行包及本地 Maven 发布通过；两端归档均无 Protobuf 类。
- 真实 Slick/LibRocket 客户端通过原生 TCP 加入、开局、发送原生移动指令并推进到 tick 301，移动 117.20 像素，本机 ping 1 ms。
  未创建逐连接 ReceiveWorker / SendWorker，客户端与服务端均无摘要偏离记录；控制台 stop 正常退出。
- AOI 扩展完成真实客户端加入、切图、AI 开局、寻路、移动和隐藏敌军过滤，摘要偏离为零，宿主正常退出。

证据：`rwpp-server/build/native-netty-final-build.log`、`native-netty-aoi.log`、
`native-tcp-desktop.log` 及对应的 `*-client.log` / `*-server.log`。
原版桌面探针源码位于 `src/test/java/io/github/rwpp/server/validation/NativeTcpDesktopProbe.java`。
上述为本机验证；未据此推算公网延迟改善比例。Android 构建仍受下述既有 SDK 版本问题限制。

## 前一轮模块调整

当前结构已将声音池、OpenGL 和服务注册直接合并到 `rwpp-client`，移除独立客户端能力模块。
客户端 JVM 编译与扩展打包、共享 API 测试、服务端检查及 `installDist` 均通过。
构建日志：`rwpp-server/build/merge-client-capabilities.log`。

## 客户端能力拆分验证（历史）

本轮将声音池与 OpenGL 迁入 `rwpp-client-api`，服务端仍只依赖共享 `rwpp-api`。
共享 API、客户端能力、服务端共 11 项 JVM 测试通过；RWPP 客户端 JVM 主源码编译和
`rwppJvmExtensionJar` 构建通过，归档检查包含声音池、OpenGL 和客户端服务注册。
服务端依赖检查、`installDist`、`distZip` 通过，ZIP 从 29,951,284 字节降至 23,311,540 字节。
逐个检查发行包 JAR：没有 Korlibs、API 音频或 OpenGL 封装类，API 的 `android/` 条目仍为零。

原版无头 server 使用原生游戏资源运行 60 帧并正常退出。AOI-Veil 使用本轮 server/API 运行，
真实 Slick/LibRocket 客户端完成加入、切图、AI 开局、移动和寻路；747 次组提交、98 次摘要匹配、
0 次偏离、3 次原子恢复，移动 137.11 像素、tick 单调、最大反向校正为零、隐藏敌军未下发。
控制台 status/stop 成功，宿主退出码 0，测试进程均已停止。

限制：未完成 Android 构建，现有 OkHttp 5.5.0 的 Android 变体要求 compileSdk 37，
项目配置及本机 SDK 为 36。`rwpp-client:jvmTest` 的旧测试仍引用已迁移/删除的
GameResumeMixin、DesktopNetworkCommandMixin、UiSurface、installRwppEngineStage 等入口，
在编译测试源码时失败；客户端主源码编译、扩展打包和上述 AOI 实机验证均通过。

证据：`rwpp-server/build/client-separation-build-jvm.log`、`client-separation-package.log`、
`client-separation-native.log`、`client-separation-smoke.log`；Android 失败记录位于
`client-separation-build.log`，旧客户端测试失败记录位于 `client-separation-client.log`。

## AOI 服务端扩展验证

当前 AOI-Veil 已完成宿主扩展化：由 `rwpp-server` 加载 server 与 API provider 两个归档，
不再使用 AOI 独立启动入口。宿主统一初始化无头平台、加载 Mixin、派发共享 API 协程并管理退出。
已通过真实桌面客户端的加入、切图、AI 开局、移动、寻路、兴趣过滤以及控制台 status/stop。

安装包位于 `E:/AOI-Veil/server/build/distributions/aoi-veil-server-0.1.0-rwpp-extensions.zip`。
完整迁移说明和最终验证证据见 `E:/AOI-Veil/docs/RWPP-SERVER-EXTENSION.md`。
下文是此前 API 迁移轮次的历史记录，其“仍用独立入口”的边界已由当前轮次替代。

## 前一轮 API 兼容性验证（历史）

## 修改

- 删除 `rwpp-api/src/jvmMain/kotlin/android/` 的十个重复兼容实现；兼容实现由 server 提供。
- 检查最终 `rwpp-api-jvm-1.0.2.jar`，其中 `android/` 条目为零。
- 恢复 AOI-Veil 原 KCP 数据报接纳逻辑：处理 Netty `DatagramPacket`、按小端序校验所有 KCP 分段、限制初始序号并回收失败接纳的配额。
- 恢复客户端 UDP socket 的收发缓冲区设置，避免延迟下的大基线挤满系统默认缓冲区。
- 新增真实 UDP 往返、分片及非法头部回归测试。
- 调整 AOI-Veil 的桌面验证探针，使它能读取独立、未混淆的共享 API provider；修正传输包装变化后的测试反射字段。

## 自动验证

RWPP：`:rwpp-api:jvmTest :rwpp-server:test :rwpp-server:installDist` 通过。

AOI-Veil：`:server:shadowJar :api:test :client:test :server:test` 通过。

| 模块       | 测试数 | 失败 | 跳过 |
|------------|-------:|-----:|-----:|
| AOI API    |     41 |    0 |    0 |
| AOI client |    106 |    0 |    0 |
| AOI server |    176 |    0 |    1 |

客户端包含 100ms 往返延迟、2% UDP 丢包下的分片基线和 60Hz 组帧验证。
跳过项是原生房间规则的显式属性门控测试。

## 真实桌面与服务端

使用独立 Windows/Java 25 进程，在 `E:/AOI-Veil/build/rwpp-migration-client` 和
`E:/AOI-Veil/build/rwpp-migration-server` 分别运行客户端、服务端，本机端口 25126。
客户端运行真实 Slick/LibRocket/RWML 1.0.2 引擎与最终 ProGuard 客户端归档；探针通过游戏原生菜单方法操作，使用 `-nodisplay`
，没有人工点击或截图验收。
客户端的 RWPP API provider 从本轮构建的 API 与依赖打包，仅用于隔离验证，不含 Android 兼容类。

验证通过：原生加入、大厅、切换 Big Island 地图、添加 AI、开局、预测移动、原生寻路、持续组同步、隐藏敌军未下发。
最终记录：749 次组提交、98 次摘要匹配、0 次摘要偏离、3 次原子恢复；移动约 137.11 像素，tick 单调递增，最大反向校正为 0。
日志中的 `checkpoint=0` 指保留的起始完整检查点；后续持续推进的是组帧。
测试完成后已停止本轮启动的两端进程。

本轮 AOI 服务端仍使用其独立启动入口，验证的是更新后的 RWPP API 与现有 AOI 两端的兼容性；不代表 AOI 已完成改为 rwpp-server
扩展，也不代表公网长时间多人对局验证。

## 本机证据

- `E:/AOI-Veil/build/rwpp-migration-final-tests.log`
- `E:/AOI-Veil/build/rwpp-migration-native-client.log`
- `E:/AOI-Veil/build/rwpp-migration-native-server.log`
- `E:/AOI-Veil/build/rwpp-migration-native.py`：本轮隔离验证驱动。
- `D:/RWPP/rwpp-server/build/kcp-regression-final.log`
- `D:/RWPP/rwpp-server/build/migration-build-final.log`

最终产物 SHA-256：

| 产物                          | SHA-256                                                          |
|-------------------------------|------------------------------------------------------------------|
| rwpp-api-jvm-1.0.2.jar        | c59e24cb96bb9fae31d504bb1c25cec9ddd216c805ea13ec731062214eaafd32 |
| aoi-veil-server-0.1.0-all.jar | 7b8027487657228f68e78900062b7c497069b5307d84ba977ccfa463c21ae406 |
| aoi-veil-client-0.1.0-all.jar | e868467daf49cde9f8fd3ee0bd9a66b68b80188b77e40712361c1eaf7e798144 |

## 原生管理命令与启动回归（2026-10-25）

从 AOI-Veil 迁入通用房间管理命令：地图/玩家列表、AI、队伍和位置、初始资金/单位、收入、迷雾、核弹、共享控制、语言、提示符及原版回放控制。
`return` 使用原版返回房间路径。命令列表见 README；未引入兴趣过滤算法、分组同步或 AOI 专用房主/结算策略。

在独立运行目录实际启动最终 installDist，端口 63473，使用 Steam 原版 assets/res；未操作游戏窗口，也没有外部客户端连接。已执行
credits 10000、startingunits 1、income 1.5、fog los、sharedcontrol on、nukes off、addai 1、players、replay on、start、replay
status、return、status 和 stop，确认录制 active → 返回大厅 → 录制等待下一局。实际生成原版 replay 文件并在返回时结束录制；最初一轮文件大小
6969 字节。

日志过滤覆盖 GameEngine.logInternal 及绕过它的原生 Android Log 普通输出，仅在 dedicated 模式生效；保留真正警告、错误、异常堆栈及
RWPP 标签。新增回归实际生成并加载变换后的 GameEngine/Log 字节码；补齐 Android Wi-Fi 兼容链接类型，避免 SafeClassWriter
帧计算导致启动失败。

客户端另有 118 项 UI 回归及 JVM/Android 独立双扩展整合包验证，详见 docs/CLIENT-UI-VALIDATION.md。本轮未验证多人客户端连接、长时间对局或游戏帧率。

最终 server 全量 66 项测试通过，失败/错误/跳过均 0；`:rwpp-server:test :rwpp-server:installDist` 成功，日志
build/server-logging-release.log，XML build/validation/server-admin-results。最终真实进程 exit 0，启动/开局/返回/退出期间未出现已识别的普通
GameEngine 初始化、network/music、MapLayer、StateEngine、空游戏线程和空 Activity 日志；原版 Map type、目录缺失及 Stale
playerTeam 警告保持可见。最终控制台记录 build/server-admin-final-smoke.log。
