# RWPP

[English](README.md)

RWPP 为《铁锈战争》提供带 Aero 界面的桌面和 Android 客户端，以及无界面服务端。两者都以 [RWML](https://github.com/RWPP-Team/RWML) 扩展的形式运行。

## 安装

先安装 [RWML](https://github.com/RWPP-Team/RWML/releases/latest)，再从 [RWPP 最新发布页](https://github.com/RWPP-Team/RWPP/releases/latest) 下载并导入对应平台的扩展包。安装后重启游戏。详见[安装教程](https://rwpp-team.top/rwpp/install/)。

## 构建

配置 JDK 25 和 Android SDK 后运行 `./gradlew build`（Windows 使用 `gradlew.bat build`）。扩展包和校验文件位于 `build/release-assets`。

## 文档

- [客户端功能](https://rwpp-team.top/rwpp/features/)
- [扩展 API](rwpp-api/README.md)与[界面扩展指南](docs/UI-CUSTOMIZATION.zh.md)
- [无界面服务端](rwpp-server/README.md)

## 致谢

- [RW-HPS](https://github.com/deng-rui/RW-HPS)
- [JDSherbert](https://jdsherbert.itch.io/) 提供音效

## 许可

[RWPP-Team Shield 许可证](LICENSE.md)
