/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwpp.api.i18n.readI18n

internal actual fun resolveCommunityDlss5Directory(controllerDirectory: String): String = controllerDirectory

internal actual fun launchCommunityDlss5(controllerDirectory: String): String =
    readI18n("rwpp.dlss5.error.windows")

internal actual fun queryCommunityDlss5Status(controllerDirectory: String) =
    CommunityDlss5Status(errorKey = "rwpp.dlss5.error.windows")

internal actual fun closeCommunityDlss5(controllerDirectory: String): String = readI18n("rwpp.dlss5.error.windows")

internal actual fun enableCommunityDlss5(controllerDirectory: String): String = readI18n("rwpp.dlss5.error.windows")
