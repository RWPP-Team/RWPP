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
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.10.1-brightgreen)](https://www.jetbrains.com/lp/compose-multiplatform/)
![Android](https://img.shields.io/badge/Android-green)
![Desktop](https://img.shields.io/badge/Desktop-tomato)
[![License](https://img.shields.io/github/license/Minxyzgo/RWPP)]()
[![Issues](https://img.shields.io/github/issues/Minxyzgo/RWPP)]()
</div>

<h1 align="center"> Screenshot </h1>

![screenshot](/.github/img.png)

<h1 align="center"> Implemented </h1>

 - __Ban Units__
 - __IME fixed__
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

## Msi Version
- Install RWPP to your rw root directory
> e.g. SteamLibrary\steamapps\common\Rusted Warfare\
- Run RWPP.exe

## Jar Version
- Install Java 17
- Copy RWPP.jar to your rw root directory.
> e.g. SteamLibrary\steamapps\common\Rusted Warfare\
- Run launcher.bat

<h1 align="center"> Build </h1>

- Use OpenJdk 17 or above.
- For desktop, run task `rwpp-desktop:packageReleaseUberJarForCurrentOS`
- To build msi, run task `rwpp-desktop:packageWixDistribution` (Note that you need .net SDK)
- For android, assets and res are missing for some reason, you can find them in your Rusted Warfare client.

<h1 align="center"> Contribute </h1>

If you find any bugs or have any suggestions, please create an issue.

<h1 align="center"> Thanks </h1>

[RW-HPS](https://github.com/deng-rui/RW-HPS)

Sounds by [JDSherbert](https://jdsherbert.itch.io)
