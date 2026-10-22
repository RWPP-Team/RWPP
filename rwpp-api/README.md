# RWPP 游戏 API

`:rwpp-api` 是 RWPP 仓库中的 Kotlin Multiplatform 模块，发布 JVM 与 Android 变体。RWML 仓库只提供
Java 加载器和宿主，不包含或暂存该模块；RWPP 扩展把 API 类与自身实现合并，作为一个扩展加载。
独立 API 归档仍可单独构建，RWPP `:rwpp-server` 发行包也会携带该归档并在扩展发现前暂存。
游戏类使用 `compileOnly`，不会随 API 再打包一份。

`commonMain` 提供唯一的 Game、GameRoom、Net、ConfigIO、声音池和游戏线程 dispatcher 实现。
地图加载、连接、地图枚举等共用逻辑直接调用映射后的引擎；平台 source set 仅处理资源路径、
本机窗口/Activity、浏览器及 OpenGL 调用。`graphics` 的包名为 `io.github.rwml.api.graphics`。

API 的 `ApiExtension.onLoad` 使用 Koin compiler plugin 和 `RwmlApiModule` 注解启动共享容器。
消费扩展通过 `ApiRuntime.configureKoin` 注册自己的注解模块。引擎初始化完成时，API hook
运行 `Initialization` 组件，然后广播游戏事件。dispatcher 在 Android 原生
循环空闲时由主线程 pump 推进，循环运行时交给帧 hook；API 自行处理暂停和恢复的交接。
所有事件统一使用协程 `EventChannel` / `GlobalEventChannel`。原生可取消入口等待 `broadcast()` 完成后再读取取消结果，监听器支持 suspend。
消费方不再注册这些游戏 hook 或自行实现游戏服务。

游戏共用 hook 位于 `commonMain`。平台差异应落在实际的资源、窗口和调度操作中，
不以 Android/JVM 的 source set 名称复制引擎事件逻辑。旧的 `graphics.GL` 辅助对象已删除。

原生菜单中的建房、加入/重连、地图加载、回放和模组重载同样通过 API hook 更新状态及广播；
从消费方调用 API 时也走这些入口，不需要消费方再次广播事件。

声音池在两个平台统一使用 KorAU。ConfigIO 的序列化和游戏设置访问也只有一个实现，Android
持久化使用 SharedPreferences，JVM 使用 TOML 与 properties 文件。

翻译统一使用 UTF-8 properties。API 的 i18n 包仅提供 `readI18n(key, vararg args)`，游戏就绪后
直接调用 `LocaleEngine.getString`，不再提供 I18nType、GameI18nResolver 或 TOML 翻译表。
宿主通过原生 LocaleEngine 的 ResourceBundle 注册自身及扩展词条，每次游戏语言重载都会
恢复这些注册。扩展通过宿主 `Messages.register` 提供 properties；RWPP 使用 `rwpp.*` 命名空间。
游戏尚未加载时，启动界面读取同一批 properties；游戏加载后统一由原生翻译引擎处理。

命令注册、默认 help 的权限/可见性过滤，以及多前缀聊天与控制台路由见
[Command API 文档](https://github.com/RWPP-Team/RWML/blob/master/docs/commands.md)。

`Game.gameModes` / `GameRoom.gameModes` 提供可组合的游戏规则：基础模式互斥，修改器按依赖、
冲突和顺序组合。禁用单位、游戏速度与地图变换均使用这些模式，原生 skirmish/survival 也在
同一注册表中，见 [GameMode 文档](https://github.com/RWPP-Team/RWML/blob/master/docs/game-modes.md)。
`AiManager` 提供原生 AI 创建、难度、暂停、命令及可卸载的自定义控制器；共享 hook 在
`commonMain`，见 [AI API 文档](https://github.com/RWPP-Team/RWML/blob/master/docs/game-ai-api.md)。

RWPP 的 `:rwpp-core` 在编译时通过项目依赖使用 `:rwpp-api`，并将其实现合并进自身 JVM/Android 扩展归档，
因此这个内置扩展不再声明对独立 API 扩展的运行时依赖。独立归档继续使用稳定 ID `io.github.rwml.api`；
RWPP 内的扩展服务则由 `io.github.rwpp` 提供。需要单独消费 API 的扩展仍可使用
发布的 `io.github.rwpp-team:rwpp-api` 编译变体；其运行时需由提供相同 API 的宿主扩展加载。

```properties
dependsOn=io.github.rwml.api
```

RWML Android 不再携带 Compose、Coil 或 API 运行库；这些 UI 与扩展依赖由 RWPP 自身管理。
API 的 Android 归档包含其运行所需依赖，但复用宿主提供的 Kotlin 标准库、`rwml-core` 与 ASM。

```powershell
# E:\RWML：发布宿主构件，再在 D:\RWPP 构建使用它们的模块
.\gradlew.bat :core:publishToMavenLocal :desktop:publishHostPublicationToMavenLocal

# D:\RWPP
.\gradlew.bat :rwpp-api:jvmExtensionJar :rwpp-api:androidExtensionJar
.\gradlew.bat :rwpp-core:rwppJvmExtensionJar :rwpp-core:rwppAndroidExtensionJar
.\gradlew.bat :rwpp-server:installDist
```

独立 API 归档分别写入 `rwpp-api/build/extensions/jvm/rwpp-api.jar` 和
`rwpp-api/build/extensions/android/rwpp-api.jar`。RWML 宿主不会自动安装它；RWPP 扩展归档将 API 与 RWPP
实现合并，服务器发行包则在 `rwpp-server/build/install/rwpp-server/lib/` 中提供独立 API 归档，并在启动时暂存。

`publishGameLibForIde` 把映射二进制和源码写入不可变的 Maven revision，通过
`build/local-maven/current-repository.txt` 指向当前版本。消费工程的 Gradle 同步读取该指针，
因此生成新映射时不会清理 IDE 正在读取的源码 jar。原始游戏归档缺失的具名嵌套类
`InnerClasses` 元数据也会在 remap 时恢复，使 `Paint.Style` 等类型能被编译器与反编译器识别。
remap 同时修复被混淆成 `SourceFile` 的源码文件名属性。生成产物也按输入摘要隔离，
模块统一通过带源码分类器的 Maven 坐标依赖游戏。映射变化后先单独执行
`publishGameLibForIde`，再重载 Gradle/启动消费构建，见 [源码导航说明](https://github.com/RWPP-Team/RWML/blob/master/docs/game-lib/README.md#idea-source-navigation)。

## 原生事件和接收器

所有事件继承 `AbstractEvent`，通过同一个 `GlobalEventChannel` 注册和广播。
事件类型集中在 `io.github.rwml.api.event.events`；`io.github.rwml.api.event` 保留事件通道、
监听器与发布器入口。
`broadcast()` 挂起至监听器完成，默认保留调用协程的上下文；需要游戏状态操作时显式使用
`withContext(GameMainDispatcher)`，异步任务使用 `GameScope.launch`。
`broadcastIn()` 是显式异步通知，不能用于需要立即读取取消结果的原生入口。

```kotlin
import io.github.rwml.api.event.EventOwner
import io.github.rwml.api.event.GlobalEventChannel
import io.github.rwml.api.event.events.ChatMessageEvent

val owner = EventOwner(context)
GlobalEventChannel.filter(ChatMessageEvent::class).subscribeAlways(owner) { event ->
    if (event.message.length > 2000) event.cancel()
}
```

`cancel()` 取消对应原生默认操作，后续监听器仍能观察事件；`intercept()` 跳过后续普通监听器，
MONITOR 仍可观察结果。通知事件的 cancel 不会撤销已经完成的原生操作。
`Listener.close()` 或 `complete()` 注销监听器。`EventOwner` 仅管理资源释放，
扩展卸载、宿主关闭或 onLoad 失败时通过 `ExtensionContext.own` 关闭注册。

帧、模拟、地图、连接、房间、回放、模组、单位、队伍与绘制均由 commonMain 原生 hook 提供。
同一事实只广播一个事件，RWPP 与 AOI-Veil 直接订阅此通道；没有独立 invoker 或第二个派发回路。
原生对象遵循调用边界的有效范围，异步 UI 应先复制字符串和数值。通用收包由 API 提供，
RWPP 只注册自己的报文与产品策略。

原生帧、模拟和绘制高频事件使用按线程/嵌套深度复用的实例。监听器完成前可挂起；完成后不得
保留借出的事件或绘制上下文，需要保存时复制具体数据。事件归还会清引用及取消/拦截状态。
这些事件保留公开构造器与只读属性，已移除 `data class` 自动生成的 `copy` 和解构方法。
真实桌面游戏的 1000 单位对照、分配数据和复现步骤见
[高频事件性能报告](https://github.com/RWPP-Team/RWML/blob/master/docs/high-frequency-events-performance.md)。

当前版本进一步移除了每单位 overlay 的反射 Operation；空订阅走原生矩形调用，挂起广播仍
使用同一个 GlobalEventChannel。原生帧率对照见
[性能报告](https://github.com/RWPP-Team/RWML/blob/master/docs/event-native-baseline-performance.md)。

具体线程、成功条件和注册示例见 [原生 API 契约](https://github.com/RWPP-Team/RWML/blob/master/docs/api-native-events.md)。
