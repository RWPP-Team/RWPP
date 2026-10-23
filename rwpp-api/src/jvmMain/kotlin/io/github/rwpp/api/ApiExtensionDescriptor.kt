/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api

import io.github.rwml.core.ExtensionDescriptor
import io.github.rwml.core.ExtensionDescriptorReader

/**
 * 把 API 作为内置扩展安装时使用的描述符。
 *
 * API 平时由使用它的扩展归档携带，宿主扫描归档即可得到描述符。宿主把 API 放进自己的类路径时没有归档可扫描，
 * 因此由宿主通过本对象取得同一份描述符。构建时把模块描述符复制到专属资源路径，避免类路径上的其他扩展
 * 使用同名 `META-INF/extension.properties` 时发生冲突，两份声明不会各自漂移。
 *
 * 内置扩展没有源归档，`ExtensionManager` 会用宿主自己的类加载器实例化它，并跳过归档扫描。
 */
object ApiExtensionDescriptor {

    /** 描述符中声明的扩展 ID，也是宿主判断 API 是否加载成功的依据。 */
    const val ID = "io.github.rwpp.api"

    private const val RESOURCE = "/META-INF/rwpp-api/extension.properties"

    /**
     * @return 无源归档的内置描述符
     * @throws IllegalStateException 类路径上的描述符缺失或不属于本 API
     */
    @JvmStatic
    fun descriptor(): ExtensionDescriptor {
        val descriptor = ExtensionDescriptorReader.readBuiltIn(ApiExtension::class.java, RESOURCE)
        check(descriptor.id() == ID) {
            "Expected $RESOURCE to declare extension '$ID', found '${descriptor.id()}'. " +
                "Another archive on the class path supplies a descriptor at the same path."
        }
        return descriptor
    }
}
