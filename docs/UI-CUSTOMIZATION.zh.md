# 使用 ComposeScopeTree 修改 RWPP 界面

桌面和 Android 的 RWPP 客户端发布包均包含 ComposeScopeTree 0.1.1。常规 `build` 和 `:releasePackages`
会编译可编辑的 RWPP 调用点。运行时从 `RwppScopeTrees.hosts` 取得当前挂载的树。Android 菜单和游戏界面可能
同时各有一棵树，因此处理每个 host，并通过节点判断所在场景。

## 扩展接入

在扩展的 `META-INF/extension.properties` 声明 `dependsOn=io.github.rwpp`。使用 Kotlin 2.4.20 的 Compose
编译器插件，在扩展项目的编译 classpath 加上对应平台的客户端归档，或在 RWPP 工作区使用
`compileOnly(project(":rwpp-client"))`。下面是独立桌面扩展的依赖写法。

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

dependencies {
    compileOnly(files("libs/rwpp-api-2.0.2-jvm.jar", "libs/rwpp-client-2.0.2-jvm.jar"))
}
```

用 RWPP 提供的 Compose 和 ScopeTree 类执行这些示例。要让扩展本身的 Composable 调用点也能被记录，再在扩展的
Kotlin 编译任务上加载 `io.github.minxyzgo:compose-scope-tree-compiler-plugin:0.1.1`，顺序为
`dev.composescopetree.compiler>androidx.compose.compiler.plugins.kotlin`。扩展声明的 UI 在 RWPP 的树下组合时，
需要通过该 host 的跟踪规则才能显示为节点。

## 改主菜单按钮的文字与外边距

以下代码把当前主菜单的战役按钮改为「战役简报」，并在按钮外加 6 dp 上间距。通过固定英文副标题定位目标，因此在
切换语言后定位规则仍有效。只覆盖 `title` 和 `modifier`，其余传参继续由原调用点提供，包括打开战役页的回调。

```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composescopetree.ImplicitScopeNode
import dev.composescopetree.ImplicitScopeTreeHost
import io.github.rwpp.ui.RwppScopeTrees
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private fun ImplicitScopeTreeHost.nodes(): Sequence<ImplicitScopeNode> {
    fun walk(node: ImplicitScopeNode): Sequence<ImplicitScopeNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::walk)
    return roots.asSequence().flatMap(::walk)
}

fun customizeCampaignButton(scope: CoroutineScope): Job = scope.launch {
    RwppScopeTrees.hosts.collectLatest { hosts ->
        coroutineScope {
            hosts.forEach { host ->
                launch {
                    snapshotFlow {
                        host.nodes().firstOrNull {
                            it.function == "io.github.rwpp.widget.v2.AeroMenuButton" &&
                                ".MainMenu.kt:" in it.site &&
                                it.arguments?.let { args ->
                                    "subTitle" in args.names &&
                                        args.getAs<String>("subTitle") == "SINGLE PLAYER CAMPAIGN"
                                } == true
                        }?.address
                    }.collectLatest edit@{ address ->
                        if (address == null) return@edit
                        host.overrideArguments(address) { args ->
                            args.with("title", "战役简报").with(
                                "modifier", args.getAs<Modifier>("modifier").padding(top = 6.dp)
                            )
                        }
                        try {
                            awaitCancellation()
                        } finally {
                            if (host.nodes().any { it.address == address }) host.resetParameters(address)
                        }
                    }
                }
            }
        }
    }
}
```

在扩展的启用回调保存返回的 `Job`，卸载时调用 `cancel()`。离开主菜单会卸载节点，再进主菜单会按新地址重新应用。
检查 `node.arguments?.names` 可发现当前调用点传入的参数。名字和类型遵循实际声明，例如尺寸参数按 `Dp`
传递。跨版本匹配用函数名和语义参数，源码行列号适合定位当前版本的问题。

## 换主菜单容器并保留两组按钮

同一套 `snapshotFlow` 也能跟踪容器。在使用横排菜单的窗口中，主菜单里有多个 `Row`，下面要求同一个候选容器的后代同时包含常用操作和
扩展入口，从而定位包住两列的那一个。找到地址后，用带接收者的 `RowDefinition` 传递原按钮内容。

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import dev.composescopetree.ui.RowComponent
import dev.composescopetree.ui.RowDefinition

// 在观察代码里用这个函数替换按钮节点的查找条件。
fun ImplicitScopeTreeHost.mainMenuRow() = nodes().firstOrNull { node ->
    fun descendants(parent: ImplicitScopeNode): Sequence<ImplicitScopeNode> =
        parent.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }
    if (".MainMenu.kt:" !in node.site || node.parameters(RowComponent) == null) return@firstOrNull false
    val names = descendants(node).map { it.function }.toSet()
    "io.github.rwpp.ui.MainMenuScreen.OperationButtons" in names &&
        "io.github.rwpp.ui.MainMenuScreen.ExtensionButtons" in names
}

// address 来自 host.mainMenuRow()?.address。卸载时调用 host.resetDefinition(address)。
host.replaceDefinition(address, RowDefinition) { parameters, children ->
    Row(
        modifier = parameters.modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = parameters.verticalAlignment,
        content = children,
    )
}
```

如果需要替换 `AeroPanel`，可用
`host.replaceCallWithSlot(address, "content") { args, children: @Composable BoxScope.() -> Unit -> … }`。
替换后的容器要为 `children` 提供 `BoxScope`，让原有的 `align` 和 `matchParentSize` 仍然有效。

## 把场景转场换成淡入

下例把默认 Aero 效果换成 220 ms 淡入。导航继续同步切换，上一页的事件监听和弹窗随着页面离开而释放。
切换 Aero 主题也会更新 `context.themeGeneration`。用户关闭「动画」时，导航器直接显示场景内容。

```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.rwpp.screen.SceneTransitionContext
import io.github.rwpp.screen.SceneTransitionEffect
import io.github.rwpp.screen.SceneTransitions

val fade = object : SceneTransitionEffect {
    @Composable
    override fun Render(context: SceneTransitionContext, content: @Composable () -> Unit) {
        val opacity = remember { Animatable(0.35f) }
        LaunchedEffect(context) {
            opacity.snapTo(0.35f)
            opacity.animateTo(1f, tween(220))
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) { content() }
    }
}

// 启用扩展时保存当前实现，再安装淡入。卸载时按后面的条件恢复。
val previous = SceneTransitions.effect
SceneTransitions.effect = fade
// 卸载回调里执行
if (SceneTransitions.effect === fade) SceneTransitions.effect = previous
```

也可以在一棵树中找到 `io.github.rwpp.screen.SceneTransition`，用
`host.overrideArguments(address) { it.with("effect", fade) }` 只改变该导航实例。按场景改效果时，
读取 `args.getAs<SceneTransitionContext>("context").screen`，对指定的 Screen 类型返回自定义效果，
其余场景保留原 `effect` 参数。自定义效果应在同一次组合中调用一次 `content()`。

完整 API 和其他调用点示例可参阅 [ComposeScopeTree 文档](https://rwpp-team.top/extension/compose-scope-tree/)。
