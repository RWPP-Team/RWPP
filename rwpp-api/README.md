# RWPP API

客户端与服务端共用的游戏 API，支持 JVM 和 Android。

## 功能

| 功能     | 内容                                   |
|----------|----------------------------------------|
| 游戏     | 房间、地图、单位、队伍、AI 与游戏模式  |
| 事件     | 原生生命周期、网络与游戏事件           |
| 网络     | 原生报文、模组分发、KCP 协议与传输契约 |
| 基础服务 | 配置、命令、翻译与游戏线程调度         |

声音和 OpenGL 由 [rwpp-client](../rwpp-client) 提供。

## 使用

```kotlin
dependencies {
    compileOnly("io.github.rwpp-team:rwpp-api:2.0.2")
    compileOnly("io.github.rwpp-team:rwml-core:1.0.3")
    compileOnly("io.github.rwpp-team:game-lib:1.0.3")
}
```

三个库均通过 Maven Central 分发。`rwml-core` 与 `game-lib` 使用相同的 RWML 版本。

扩展描述符声明 `dependsOn=io.github.rwpp.api`。客户端安装独立 API 扩展；服务端由宿主内置提供，无需重复打包。

通过 `GlobalEventChannel` 订阅事件；操作游戏状态时切换到 `GameMainDispatcher`。
扩展服务通过 `ApiRuntime.configureKoin` 加入共享容器。

## 扩展设置

在 `Extension.onLoad(context)` 中通过 `ExtensionSettings.register` 注册设置。RWPP 主菜单的「扩展」页面显示当前进程实际加载的扩展，并在对应条目下提供已注册的设置。

扩展可在 `META-INF/extension.properties` 中设置 `icon=assets/icon.png`，并把图片放入归档中的同一路径。建议使用 PNG，最长边为 512 像素以内，文件不超过 1 MiB。

```kotlin
import io.github.rwml.api.Extension
import io.github.rwml.api.ExtensionContext
import io.github.rwpp.api.extension.ExtensionSetting
import io.github.rwpp.api.extension.ExtensionSettings

class ExampleExtension : Extension {
    private var overlay = true
    private var detail = 0
    private var caption = "Example"

    override fun onLoad(context: ExtensionContext) {
        ExtensionSettings.register(context, listOf(
            ExtensionSetting.Toggle("显示叠层", { overlay }, { overlay = it }),
            ExtensionSetting.Choice("细节级别", listOf("标准", "详细"), { detail }, { detail = it }),
            ExtensionSetting.Text("标题", { caption }, { caption = it.trim() }),
        ))
    }
}
```

选择项使用从零开始的下标。开关和选择项即时提交，文本通过「应用」提交。读写回调在客户端 UI 线程执行。扩展负责校验和保存自己的设置，上例仅保存在内存中。RWML 在扩展卸载或加载失败时自动注销设置，也可提前关闭注册返回的句柄。

ModPacket 和编解码位于 `io.github.rwpp.api.net.packets`，两端共用模组分发。
API 不依赖 Netty 或 Java-KCP。客户端 JDK 收发由 client 提供，服务端 Netty 收发由 server 提供。
联机默认优先 KCP，握手无回应时回退 TCP。

## 许可

RWPP API 使用 [RWPP-Team Shield License 1.0.0](../LICENSE.md)。
