<h1 align="center">RWPP</h1>

<p align="center">Multiplatform client and headless server for Rusted Warfare.</p>

<div align="center"><img alt="RWPP" src="./rwpp-client/src/commonMain/composeResources/drawable/logo.png" width="100"></div>

<div align="center">
  <a href="https://kotlinlang.org"><img alt="Kotlin 2.4.20" src="https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin"></a>
  <a href="https://www.jetbrains.com/lp/compose-multiplatform/"><img alt="Compose Multiplatform 1.13.0-beta01" src="https://img.shields.io/badge/Compose%20Multiplatform-1.13.0--beta01-brightgreen"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-green">
  <img alt="Desktop" src="https://img.shields.io/badge/Desktop-tomato">
  <a href="./LICENSE.md"><img alt="License" src="https://img.shields.io/github/license/RWPP-Team/RWPP"></a>
</div>

<p align="center">🇺🇸 English | 🇨🇳 <a href="./README.zh.md">简体中文</a></p>

## Contents

[Introduction](#introduction) · [Installation](#installation) · [Usage](#usage) · [Build](#build) · [Extensions](#extensions) · [Credits](#credits) · [License](#license)

## Introduction

RWPP adds an Aero client for desktop and Android and a headless server to Rusted Warfare. Both run as [RWML](https://github.com/RWPP-Team/RWML) extensions.

| Module | Role |
|--------|------|
| `rwpp-api` | Shared game and extension interfaces |
| `rwpp-client` | Aero interface, configurable HUD, multiplayer tools and replay browser |
| `rwpp-server` | Headless server with a console and extension loading |

## Installation

1. Install [RWML 1.0.3 or later](https://github.com/RWPP-Team/RWML/releases/latest).
2. Import the desktop or Android pack from the [latest RWPP release](https://github.com/RWPP-Team/RWPP/releases/latest) into RWML.
3. Restart the game. If importing individual JARs, use the API and client from the same release.

[Installation guide](https://rwpp-team.top/rwpp/install/)

## Usage

Use **Settings → Client** to configure gameplay options, **Extensions** to view settings for loaded extensions, and **Resource Browser** to find mods and maps. [Client guide](https://rwpp-team.top/rwpp/features/) · [Server guide](rwpp-server/README.md)

## Build

With JDK 25 and the Android SDK configured, run `./gradlew build` (`.\gradlew.bat build` on Windows). Packs and checksums are written to `build/release-assets`. To build only the release files, run `./gradlew :releasePackages`.

## Extensions

Use matching versions of `rwml-core` and `game-lib` as compile-only dependencies. Add `io.github.rwpp-team:rwpp-api` when you need shared game services. [API reference](rwpp-api/README.md) · [ComposeScopeTree examples](docs/UI-CUSTOMIZATION.zh.md)

## Credits

- [RW-HPS](https://github.com/deng-rui/RW-HPS)
- [JDSherbert](https://jdsherbert.itch.io/) for the sound effects

## License

[RWPP-Team Shield License](LICENSE.md)
