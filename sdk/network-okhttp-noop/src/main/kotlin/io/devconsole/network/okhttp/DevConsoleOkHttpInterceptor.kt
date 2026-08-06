package io.devconsole.network.okhttp

import io.devconsole.network.NetworkTransactionRecorder
import okhttp3.Interceptor
import okhttp3.Response

/** Protected-build adapter: forwards the host request without inspecting or allocating capture data. */
class DevConsoleOkHttpInterceptor
    @JvmOverloads
    constructor(
        @Suppress("UNUSED_PARAMETER") recorder: NetworkTransactionRecorder,
        @Suppress("UNUSED_PARAMETER") timingsProvider: DevConsoleOkHttpEventListenerFactory? = null,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
    }
