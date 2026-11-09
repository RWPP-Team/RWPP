<h1 align="center">RWPP</h1>

<p align="center">Multiplatform client and headless server for Rusted Warfare.</p>

<div align="center"><img alt="RWPP" src="./rwpp-client/src/commonMain/composeResources/drawable/logo.png" width="100"></div>

<div align="center">
  <a href="https://kotlinlang.org"><img alt="Kotlin 2.4.20" src="https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin"></a>
  <a href="https://www.jetbrains.com/lp/compose-multiplatform/"><img alt="Compose Multiplatform 1.13.0-beta01" src="https://img.shields.io/badge/Compose%20Multiplatform-1.13.0--beta01-brightgreen"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-green">
  <img alt="Desktop" src="https://img.shields.io/badge/Desktop-tomato">
  <a href="./LICENSE.md"><img alt="License" src="https://img.shields.io/github/license/Minxyzgo/RWPP"></a>
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

The client adds unit bans, IME input, blacklists, room list filters, additional room options, external resources and a
new interface. The server runs without a display device and uses the same API.

## Installation

RWPP is loaded by RWML. Install the [latest release](https://github.com/Minxyzgo/RWPP/releases/latest) and import the
archive for the target platform through the launcher. The archive provides `io.github.rwpp.api` and `io.github.rwpp`.
The two JAR files can also be imported separately.

Both extensions must come from the same release. The client declares a dependency on the API and registers its own
interface framework.

## Usage

Restart the game after enabling the extensions. The client takes over the launcher and draws its interface above the
game. Room options, the action panel and the information HUD follow the rules of the original game.

The server accepts KCP and TCP on the same port. The client prefers KCP and falls back to TCP when the handshake
receives no reply. Console commands and startup options are listed in [rwpp-server/README.md](rwpp-server/README.md).

## Extensions

An extension depends on `rwml-core` and the mapped game library. Extensions that need shared game services also depend
on `rwpp-api`. The host provides these libraries at runtime, so all three are compile-only
dependencies. [rwpp-api/README.md](rwpp-api/README.md) describes the event channel, the command registry and the
configuration services.

## Credits

[RW-HPS](https://github.com/deng-rui/RW-HPS)

Sounds by [JDSherbert](https://jdsherbert.itch.io)

## License

The project is distributed under the terms in [LICENSE.md](LICENSE.md).
