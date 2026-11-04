<div align="center">
<h1> RWPP </h1>
<div align="center">
  <strong>multiplatform client&server for Rusted Warfare</strong>
</div>
<br />
<div align="center">
 <img src = "https://github.com/Minxyzgo/RWPP/blob/main/rwpp-core/src/commonMain/composeResources/drawable/logo.png" width = "100px"/>
</div>
<br />

----
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.13.0--beta01-brightgreen)](https://www.jetbrains.com/lp/compose-multiplatform/)
![Android](https://img.shields.io/badge/Android-green)
![Desktop](https://img.shields.io/badge/Desktop-tomato)
[![License](https://img.shields.io/github/license/Minxyzgo/RWPP)]()
[![Issues](https://img.shields.io/github/issues/Minxyzgo/RWPP)]()
</div>

<h1 align="center"> Screenshot </h1>

![screenshot](/.github/img.png)

<h1 align="center"> Implemented </h1>

 - __Ban Units__
 - __IME support__ (Windows Chinese submission is under verification)
 - __Blacklists__
 - __List filter__
 - __More room options__ (Such as lock room and team look)
 - __External Resource__
 - __New UI__

<h1 align="center"> Download </h1>

You can download RWPP release version in the releases.

<h1 align="center"> Run </h1>

独立服务端的构建、资源目录和后台启动方式见 [rwpp-server/README.md](rwpp-server/README.md)。
共享层、可选客户端能力及两端宿主的职责见 [项目结构](docs/PROJECT-STRUCTURE.md)。

客户端由 RWML 加载。导入对应平台的整合 ZIP，或分别安装 `rwpp-api`、`rwpp-client` 两个扩展 JAR。
client 依赖 API，并独立声明 UI Framework；两个扩展保持各自的描述符和入口。

## Build

使用 JDK 25；Android 构建还需 SDK 36。

```powershell
.\gradlew.bat extensionPack                         # JVM
.\gradlew.bat extensionPack -PextensionPlatform=android
```

ZIP 位于 `build/distributions/`，包含两个独立 JAR 和 RWML 整合包清单。
也可直接执行 `rwppJvmExtensionPack` / `rwppAndroidExtensionPack`。

单独构建：`:rwpp-api:rwppJvmExtensionJar`、`:rwpp-client:rwppJvmExtensionJar`；Android 将 `Jvm` 换为 `Android`。
对应 JAR 位于各模块的 `build/extensions/`。
运行 `verifyJvmExtensionPack` / `verifyAndroidExtensionPack` 可检查导入、依赖、界面声明和共享类边界。

<h1 align="center"> Contribute </h1>

If you find any bugs or have any suggestions, please create an issue.

<h1 align="center"> Thanks </h1>

[RW-HPS](https://github.com/deng-rui/RW-HPS)

Sounds by [JDSherbert](https://jdsherbert.itch.io)
