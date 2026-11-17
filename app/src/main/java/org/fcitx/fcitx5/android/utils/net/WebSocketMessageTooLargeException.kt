/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils.net

import java.io.IOException

/** WebSocket 单条消息超过接收上限。 */
internal class WebSocketMessageTooLargeException(message: String) : IOException(message)
