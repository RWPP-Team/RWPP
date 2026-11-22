# RWPP

[简体中文](README.zh.md)

RWPP provides the Aero client for desktop and Android and a headless server for Rusted Warfare. Both run as [RWML](https://github.com/RWPP-Team/RWML) extensions.

## Installation

Install [RWML](https://github.com/RWPP-Team/RWML/releases/latest), then import the desktop or Android pack from the [latest RWPP release](https://github.com/RWPP-Team/RWPP/releases/latest). Restart the game after installation. See the [installation guide](https://rwpp-team.top/rwpp/install/).

## Build

With JDK 25 and the Android SDK configured, run `./gradlew build` (`gradlew.bat build` on Windows). The extension packs and checksums are in `build/release-assets`.

## Documentation

- [Client features](https://rwpp-team.top/rwpp/features/)
- [Extension API](rwpp-api/README.md) and [UI customization](docs/UI-CUSTOMIZATION.zh.md)
- [Headless server](rwpp-server/README.md)

## Credits

- [RW-HPS](https://github.com/deng-rui/RW-HPS)
- [JDSherbert](https://jdsherbert.itch.io/) for the sound effects

## License

[RWPP-Team Shield License](LICENSE.md)
