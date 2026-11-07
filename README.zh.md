<h1 align="center">RWPP</h1>

<p align="center">Rusted Warfare 的多平台客户端与无界面服务端。</p>

<div align="center"><img alt="RWPP" src="./rwpp-client/src/commonMain/composeResources/drawable/logo.png" width="100"></div>

<div align="center">
  <a href="https://kotlinlang.org"><img alt="Kotlin 2.4.20" src="https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin"></a>
  <a href="https://www.jetbrains.com/lp/compose-multiplatform/"><img alt="Compose Multiplatform 1.13.0-beta01" src="https://img.shields.io/badge/Compose%20Multiplatform-1.13.0--beta01-brightgreen"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-green">
  <img alt="Desktop" src="https://img.shields.io/badge/Desktop-tomato">
  <a href="./LICENSE.md"><img alt="License" src="https://img.shields.io/github/license/Minxyzgo/RWPP"></a>
</div>

<p align="center">🇨🇳 简体中文 | 🇺🇸 <a href="./README.md">English</a></p>

## 目录

[简介](#简介) · [安装](#安装) · [使用](#使用) · [扩展开发](#扩展开发) · [致谢](#致谢) · [许可](#许可)

## 简介

RWPP 是 Rusted Warfare 的客户端与无界面服务端。两者都以 [RWML](https://github.com/RWPP-Team/RWML) 扩展的形式运行。RWML 负责在桌面端与 Android 上加载模组。

| 模块 | 职责 |
| --- | --- |
| `rwpp-api` | 两端共用的游戏、事件、网络、命令与配置接口 |
| `rwpp-client` | 界面、输入、声音、图形与 JVM 网络后端 |
| `rwpp-server` | 带控制台与扩展加载能力的无界面服务端 |

客户端提供单位禁用、输入法支持、黑名单、房间列表筛选、更多房间选项、外部资源与新界面。服务端不依赖显示设备，并使用同一套 API。

## 安装

RWPP 由 RWML 加载。先安装[最新版本](https://github.com/Minxyzgo/RWPP/releases/latest)，再通过启动器导入对应平台的整合归档。归档同时提供 `io.github.rwpp.api` 与 `io.github.rwpp`，两个 JAR 也可以分别导入。

两个扩展必须来自同一个发行版本。客户端声明对 API 的依赖，并独立注册自己的界面框架。

## 使用

启用扩展后重启游戏。客户端接管启动流程，并把界面绘制在游戏画面之上。房间选项、动作面板与信息 HUD 均遵循原版游戏规则。

服务端在同一端口提供 KCP 与 TCP。客户端优先使用 KCP，握手无回应时回退 TCP。控制台命令与启动选项见 [rwpp-server/README.md](rwpp-server/README.md)。

## 扩展开发

扩展依赖 `rwml-core` 与映射后的游戏库。需要共用游戏服务时再引入 `rwpp-api`。这三个库在运行时由宿主提供，扩展只以编译期依赖的方式引用它们。[rwpp-api/README.md](rwpp-api/README.md) 说明事件通道、命令注册与配置服务。

## 致谢

[RW-HPS](https://github.com/deng-rui/RW-HPS)

音效由 [JDSherbert](https://jdsherbert.itch.io) 提供。

## 许可

项目按 [LICENSE.md](LICENSE.md) 中的条款分发。
