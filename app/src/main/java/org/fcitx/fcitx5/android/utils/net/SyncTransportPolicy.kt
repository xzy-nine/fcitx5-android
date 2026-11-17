/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** 阻止 HTTPS 源在重定向时静默降级为 HTTP。 */
internal object SyncTransportPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().request().url.isHttps && !chain.request().url.isHttps) {
            throw IOException("Refusing an HTTPS to HTTP redirect")
        }
        return chain.proceed(chain.request())
    }
}
