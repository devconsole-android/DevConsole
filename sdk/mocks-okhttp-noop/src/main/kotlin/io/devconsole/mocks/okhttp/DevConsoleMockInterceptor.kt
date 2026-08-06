package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockEngine
import okhttp3.Interceptor
import okhttp3.Response

/** Protected-build adapter: bypasses the mock engine and preserves the host call exactly. */
class DevConsoleMockInterceptor(
    @Suppress("UNUSED_PARAMETER") engine: MockEngine,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
