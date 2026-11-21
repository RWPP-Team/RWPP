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

[Introduction](#introduction) · [Installation](#installation) · [Usage](#usage) · [Extensions](#extensions) · [Credits](#credits) · [License](#license)

## Introduction

RWPP is the client and the headless server for Rusted Warfare. Both run as extensions
of [RWML](https://github.com/RWPP-Team/RWML), which loads modifications on desktop and Android.

| Module        | Role                                                                            |
|---------------|---------------------------------------------------------------------------------|
| `rwpp-api`    | Game, event, network, command and configuration interfaces shared by both sides |
| `rwpp-client` | Interface, input, audio, graphics and the JVM network backend                   |
| `rwpp-server` | Headless server with a console and extension loading                            |

The client adds the Aero interface and a configurable in-game HUD, multiplayer room tools, and saved-game and replay
browsers. The headless server uses the same game API.

## Installation

Install [RWML 1.0.3 or later](https://github.com/RWPP-Team/RWML/releases/latest) first. Download the Desktop or Android extension pack
from the [RWPP 2.0.1.1 release](https://github.com/RWPP-Team/RWPP/releases/tag/v2.0.1.1) and import it in the RWML launcher.
The pack installs `io.github.rwpp.api` and `io.github.rwpp`. You can also import the two JAR files separately.

Both extensions must come from the same release. The client declares a dependency on the API and registers its own
interface framework.

## Usage

Enable the extensions and start the game from the RWML launcher. On Android, the launcher prepares the extensions
before the engine loads. RWPP then opens its own menu and draws the HUD above the game.

The server accepts KCP and TCP on the same port. The client prefers KCP and falls back to TCP when the handshake
receives no reply. Console commands and startup options are listed in [rwpp-server/README.md](rwpp-server/README.md).

## Build

With JDK 25 and the Android SDK configured, run `./gradlew build` (`./gradlew.bat build` on Windows). The build produces
verified Desktop and Android extension packs, their individual API and client JARs, and `SHA256SUMS.txt` in
`build/release-assets`. Run `./gradlew :releasePackages` to build just these release files.

The optional `rwmlLocalRepo` property gives a local game mapping repository priority. If it lacks the pinned
`game-lib` version, Gradle resolves that version from Maven Central.

## Extensions

An extension depends on `rwml-core` and `game-lib` at the same RWML version. Extensions that need shared game services also depend
on `io.github.rwpp-team:rwpp-api:2.0.1` from Maven Central. The host provides these libraries at runtime, so all three are compile-only
dependencies. [rwpp-api/README.md](rwpp-api/README.md) describes the event channel, the command registry and the
configuration services.

Desktop and Android release packs include ComposeScopeTree 0.1.1 by default. The
[UI customization guide](docs/UI-CUSTOMIZATION.zh.md) shows how to edit a button, replace a layout, and customize scene transitions.

## Credits

[RW-HPS](https://github.com/deng-rui/RW-HPS)

Sounds by [JDSherbert](https://jdsherbert.itch.io)

## License

The project is distributed under the terms in [LICENSE.md](LICENSE.md).
