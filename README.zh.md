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

[简介](#简介) · [安装](#安装) · [使用](#使用) · [本地构建](#本地构建) · [扩展开发](#扩展开发) · [致谢](#致谢) · [许可](#许可)

## 简介

RWPP 为《铁锈战争》提供桌面和 Android 上的 Aero 客户端，以及无界面服务端。两者都以 [RWML](https://github.com/RWPP-Team/RWML) 扩展的形式运行。

| 模块 | 职责 |
|------|------|
| `rwpp-api` | 共用的游戏与扩展接口 |
| `rwpp-client` | Aero 界面、可配置的 HUD、多人房间工具与回放浏览 |
| `rwpp-server` | 支持控制台与扩展加载的无界面服务端 |

## 安装

1. 安装 [RWML 1.0.3 或更新版本](https://github.com/RWPP-Team/RWML/releases/latest)。
2. 从 [RWPP 最新发布页](https://github.com/RWPP-Team/RWPP/releases/latest) 下载对应平台的扩展包并导入 RWML。
3. 重启游戏。分别导入 JAR 时，API 与客户端须来自同一个发行版本。

[安装教程](https://rwpp-team.top/rwpp/install/)

## 使用

在「设置 → 客户端」调整游戏选项，在「扩展」查看已加载扩展的设置，在「资源浏览器」查找模组与地图。[功能说明](https://rwpp-team.top/rwpp/features/) · [服务端指南](rwpp-server/README.md)

## 本地构建

配置 JDK 25 和 Android SDK 后运行 `./gradlew build`（Windows 使用 `.\gradlew.bat build`）。扩展包和校验文件位于 `build/release-assets`。只构建发布文件可运行 `./gradlew :releasePackages`。

## 扩展开发

将同版本的 `rwml-core` 与 `game-lib` 声明为编译期依赖。需要共用游戏服务时引入 `io.github.rwpp-team:rwpp-api`。[API 说明](rwpp-api/README.md) · [ComposeScopeTree 界面示例](docs/UI-CUSTOMIZATION.zh.md)

## 致谢

- [RW-HPS](https://github.com/deng-rui/RW-HPS)
- [JDSherbert](https://jdsherbert.itch.io/) 提供音效

## 许可

[RWPP-Team Shield 许可证](LICENSE.md)
