# RWPP API

客户端与服务端共用的游戏 API，支持 JVM 和 Android。

## 功能

| 功能     | 内容                                   |
|----------|----------------------------------------|
| 游戏     | 房间、地图、单位、队伍、AI 与游戏模式  |
| 事件     | 原生生命周期、网络与游戏事件           |
| 网络     | 原生报文、模组分发、KCP 协议与传输契约 |
| 基础服务 | 配置、命令、翻译与游戏线程调度         |

声音和 OpenGL 由 [rwpp-client](../rwpp-client) 提供。

## 使用

```kotlin
dependencies {
    compileOnly("io.github.rwpp-team:rwpp-api:1.0.2")
    compileOnly("io.github.rwpp-team:game-lib:v3-1.15")
}
```

扩展描述符声明 `dependsOn=io.github.rwpp.api`。客户端安装独立 API 扩展；服务端由宿主内置提供，无需重复打包。

通过 `GlobalEventChannel` 订阅事件；操作游戏状态时切换到 `GameMainDispatcher`。
扩展服务通过 `ApiRuntime.configureKoin` 加入共享容器。

ModPacket 和编解码位于 `io.github.rwpp.api.net.packets`，两端共用模组分发。
API 不依赖 Netty 或 Java-KCP。客户端 JDK 收发由 client 提供，服务端 Netty 收发由 server 提供。
联机默认优先 KCP，握手无回应时回退 TCP。详见 [网络说明](../docs/NETWORK.md)。

## 构建

独立扩展：`:rwpp-api:rwppJvmExtensionJar` 或 `:rwpp-api:rwppAndroidExtensionJar`。
产物在 `rwpp-api/build/extensions/`；Android JAR 同时包含类元数据和 DEX。
API 与客户端整合 ZIP 的构建见[根目录说明](../README.md#build)。

```powershell
.\gradlew.bat :rwpp-api:jvmTest
.\gradlew.bat :rwpp-api:publishJvmPublicationToMavenLocal :rwpp-api:publishKotlinMultiplatformPublicationToMavenLocal
```

模块依赖与宿主接入见 [项目结构](../docs/PROJECT-STRUCTURE.md)。
