# RWPP Client

JVM / Android 客户端，提供界面、输入、声音、OpenGL 和 JDK 网络后端。
共享游戏服务与 KCP 协议由 [rwpp-api](../rwpp-api) 提供，客户端不引入 Netty。

联机先尝试 KCP，失败后使用原版 TCP 收发；客户端建房保留原版监听逻辑。

API 与 client 是两个扩展，client 声明 `dependsOn=io.github.rwpp.api` 和 `ui-framework`。
安装时可直接导入包含两者的 ZIP，也可分别安装两个 JAR。

| 平台 | 独立客户端 JAR | API + client 整合 ZIP |
|---|---|---|
| JVM | `:rwpp-client:rwppJvmExtensionJar` | `rwppJvmExtensionPack` |
| Android | `:rwpp-client:rwppAndroidExtensionJar` | `rwppAndroidExtensionPack` |

独立 JAR 在 `rwpp-client/build/extensions/`，整合 ZIP 在根目录 `build/distributions/`。
`extensionPack` 默认构建 JVM，使用 `-PextensionPlatform=android` 选择 Android。

地图预览由客户端加载。暂停菜单支持音量、FPS 和本地存读档；HUD 支持组件动作、拖动、缩放与布局保存。
界面回归测试：`:rwpp-client:aeroUiTest`。

无 UI 扩展可复用轻量网络归档：

```kotlin
implementation("io.github.rwpp:rwpp-client-jvm:2.0.0:network") { isTransitive = false }
```

扩展加载时安装 `TransportRuntime.installIfAbsent { JdkTransportBackend() }`。
构建：`:rwpp-client:networkRuntimeJar`。归档不包含 Compose、音频或 Netty。

贡献者页面的依赖与许可证数据由 AboutLibraries 生成。更新依赖后，在仓库根目录重新运行：

```powershell
.\gradlew :rwpp-client:exportLibraryDefinitions -PgenerateAboutLibraries=true '-PaboutLibraries.outputFile=src/commonMain/composeResources/files/aboutlibraries-jvm.json' '-PaboutLibraries.exportVariant=jvm'
.\gradlew :rwpp-client:exportLibraryDefinitions -PgenerateAboutLibraries=true '-PaboutLibraries.outputFile=src/commonMain/composeResources/files/aboutlibraries-android.json' '-PaboutLibraries.exportVariant=release'
```
