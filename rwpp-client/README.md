# RWPP Client

JVM / Android 客户端，提供界面、输入、声音、OpenGL 和 JDK 网络后端。
共享游戏服务与 KCP 协议由 [rwpp-api](../rwpp-api) 提供，客户端不引入 Netty。

联机先尝试 KCP，失败后使用原版 TCP 收发；客户端建房保留原版监听逻辑。

无 UI 扩展可复用轻量网络归档：

```kotlin
implementation("io.github.rwpp:rwpp-client-jvm:2.0.0:network") { isTransitive = false }
```

扩展加载时安装 `TransportRuntime.installIfAbsent { JdkTransportBackend() }`。
构建：`:rwpp-client:networkRuntimeJar`。归档不包含 Compose、音频或 Netty。
