<h1 align="center">RWPP</h1>

<p align="center">Rusted Warfare 的多平台客户端与无界面服务端。</p>

<div align="center"><img alt="RWPP" src="./rwpp-client/src/commonMain/composeResources/drawable/logo.png" width="100"></div>

<div align="center">
  <a href="https://kotlinlang.org"><img alt="Kotlin 2.4.20" src="https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin"></a>
  <a href="https://www.jetbrains.com/lp/compose-multiplatform/"><img alt="Compose Multiplatform 1.13.0-beta01" src="https://img.shields.io/badge/Compose%20Multiplatform-1.13.0--beta01-brightgreen"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-green">
  <img alt="Desktop" src="https://img.shields.io/badge/Desktop-tomato">
  <a href="./LICENSE.md"><img alt="License" src="https://img.shields.io/github/license/RWPP-Team/RWPP"></a>
</div>

<p align="center">🇨🇳 简体中文 | 🇺🇸 <a href="./README.md">English</a></p>

## 目录

[简介](#简介) · [安装](#安装) · [使用](#使用) · [扩展开发](#扩展开发) · [致谢](#致谢) · [许可](#许可)

## 简介

RWPP 是 Rusted Warfare 的客户端与无界面服务端。两者都以 [RWML](https://github.com/RWPP-Team/RWML) 扩展的形式运行。RWML
负责在桌面端与 Android 上加载模组。

| 模块          | 职责                                       |
|---------------|--------------------------------------------|
| `rwpp-api`    | 两端共用的游戏、事件、网络、命令与配置接口 |
| `rwpp-client` | 界面、输入、声音、图形与 JVM 网络后端      |
| `rwpp-server` | 带控制台与扩展加载能力的无界面服务端       |

客户端提供 Aero 界面、可配置的局内 HUD、多人房间工具，以及存档与回放浏览。无界面服务端使用同一套游戏 API。

## 安装

先安装 [RWML 1.0.3 或更新版本](https://github.com/RWPP-Team/RWML/releases/latest)，再从 [RWPP 2.0.2 发布页](https://github.com/RWPP-Team/RWPP/releases/tag/v2.0.2)
下载桌面或 Android 扩展整合包，在 RWML 启动器中导入。整合包包含 `io.github.rwpp.api` 与 `io.github.rwpp`，两个 JAR 也可以分别导入。

两个扩展必须来自同一个发行版本。客户端声明对 API 的依赖，并独立注册自己的界面框架。

## 使用

启用扩展后，从 RWML 启动器启动游戏。Android 启动器先准备扩展，再加载游戏引擎。引擎就绪后，RWPP 打开自己的菜单，并在游戏画面上绘制 HUD。

主菜单的「扩展」页面显示本次已加载的扩展及其注册的设置。在「设置 → 客户端」可切换战场单位配色，也可开启自动建造协助和点地行动齐发。详见[功能说明](https://rwpp-team.top/rwpp/features/)。

主菜单的「资源浏览器」支持搜索铁锈盒子的模组和地图，也可从下载周榜获取资源。下载后的 `.rwmod` 和 `.tmx` 会进入游戏原生目录。

服务端在同一端口提供 KCP 与 TCP。客户端优先使用 KCP，握手无回应时回退
TCP。控制台命令与启动选项见 [rwpp-server/README.md](rwpp-server/README.md)。

## 本地构建

配置 JDK 25 和 Android SDK 后，运行 `./gradlew build`（Windows 使用 `./gradlew.bat build`）。构建会校验桌面和 Android
扩展包，并将两个整合包、四个独立 JAR 和 `SHA256SUMS.txt` 放在 `build/release-assets`。只构建发布文件时可运行
`./gradlew :releasePackages`。

可选的 `rwmlLocalRepo` 配置会优先使用本地映射仓库。若本地仓库缺少项目指定的 `game-lib` 版本，Gradle 会从 Maven Central
解析该版本。

## 扩展开发

扩展依赖同一 RWML 版本的 `rwml-core` 与 `game-lib`。需要共用游戏服务时，从 Maven Central 引入
`io.github.rwpp-team:rwpp-api:2.0.2`。三个库均由宿主在运行时提供，扩展只需声明编译期依赖。
[rwpp-api/README.md](rwpp-api/README.md) 说明事件通道、命令注册与配置服务。

桌面和 Android 发布构建使用 `io.github.minxyzgo` 下的 ComposeScopeTree 0.1.1。[界面扩展指南](docs/UI-CUSTOMIZATION.zh.md) 演示如何修改按钮、
替换布局容器，以及为场景切换设置自定义动画。

## 致谢

感谢 [@WHT-MO](https://github.com/WHT-MO) 贡献实验性单单位寻路、Esc 导航、设置和 HUD 编辑放弃改动，以及工厂待产提示。见 [#117](https://github.com/RWPP-Team/RWPP/pull/117) 和 [#118](https://github.com/RWPP-Team/RWPP/pull/118)。

[RW-HPS](https://github.com/deng-rui/RW-HPS)

音效由 [JDSherbert](https://jdsherbert.itch.io) 提供。

## 许可

项目按 [LICENSE.md](LICENSE.md) 中的条款分发。
