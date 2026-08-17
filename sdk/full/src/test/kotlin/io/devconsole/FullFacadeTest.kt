package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.MockScope
import io.devconsole.network.NetworkRequestInput
import io.devconsole.push.PushInput
import io.devconsole.state.FeatureFlag
import io.devconsole.state.StateSnapshot
import io.devconsole.state.stateProvider
import io.devconsole.ui.compose.DevConsoleInspectorBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs under Robolectric so [Application.getPackageManager] behaves like a real device instead
 * of AGP's unit-test stub jar (which throws "not mocked" for any non-trivial platform call).
 * Pinned to SDK 34: Robolectric 4.14.1 does not yet ship an API 37 (Android 17) framework jar,
 * and this test only needs generic PackageManager/ApplicationInfo behavior.
 *
 * Only this class's first test may assert [InitResult.Initialized] against the shared
 * [DevConsole] singleton, since that object is a genuine process-wide singleton in production
 * and in this JVM; every other test that needs a guaranteed-fresh runtime uses its own
 * [PlatformFacadeProvider] instance instead, matching production's real init semantics
 * (FR-CORE-001: repeated init with the same config returns the existing runtime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullFacadeTest {
    @Test
    fun `invalid configuration returns structured errors before platform services are created`() {
        val provider = PlatformFacadeProvider()

        val result =
            provider.initialize(
                ApplicationProvider.getApplicationContext(),
                DevConsoleConfig(eventBufferCapacity = 0),
            ) as InitResult.InvalidConfiguration

        assertEquals(
            listOf(io.devconsole.api.ConfigValidationCode.INVALID_EVENT_BUFFER_CAPACITY),
            result.errors.map { it.code },
        )
        assertEquals(DevConsoleState.Uninitialized, provider.state().value)
        assertNull(provider.endpoint())
    }

    @Test
    fun `java-friendly asynchronous start returns public endpoint data`() =
        runTest {
            assertEquals(
                InitResult.Initialized,
                DevConsole.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default()),
            )

            val deferred = kotlinx.coroutines.CompletableDeferred<StartResult>()
            DevConsole.startBrowserAsync { result -> deferred.complete(result) }
            val started = deferred.await()

            assertTrue(started is StartResult.Started)
            DevConsole.stop(StopReason.UserRequested)
        }

    @Test
    fun `FR-BUILD-002 full facade initializes the enabled runtime`() =
        runTest {
            val provider = PlatformFacadeProvider()

            assertEquals(
                InitResult.Initialized,
                provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default()),
            )
            assertEquals(DevConsoleState.Initialized, provider.state().value)
            assertTrue(provider.mockEngine().isEnabled())
            val started = provider.startBrowser(StartRequest())
            assertTrue(started is StartResult.Started)
            started as StartResult.Started
            assertEquals(BindingMode.LAN, started.endpoint.bindingMode)
            assertTrue(started.endpoint.port in 8080..8099)
            assertTrue(started.access.connectUrl.contains("#code="))
            assertEquals(DevConsoleState.Running, provider.state().value)
            provider.stop(StopReason.UserRequested)
            assertEquals(DevConsoleState.Stopped, provider.state().value)
        }

    @Test
    fun `persistent mock rules survive full runtime recreation`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        val store = AndroidMockRuleStore(application)
        store.save(emptyList())
        val first = PlatformFacadeProvider()
        val second = PlatformFacadeProvider()

        assertEquals(InitResult.Initialized, first.initialize(application, DevConsoleConfig.default()))
        first
            .mockEngine()
            .upsert(
                MockRule(
                    id = "durable-recreation-test",
                    priority = 1,
                    path = "/orders",
                    scope = MockScope.PERSISTENT_INTERNAL,
                    action = MockAction.StaticResponse(200, "persisted"),
                ),
            )

        assertEquals(InitResult.Initialized, second.initialize(application, DevConsoleConfig.default()))
        assertEquals(
            "durable-recreation-test",
            second
                .mockEngine()
                .rules()
                .single()
                .id,
        )
        assertTrue(second.mockEngine().remove("durable-recreation-test"))
    }

    @Test
    fun `server stop removes session mocks without disabling durable rules`() =
        runTest {
            val application: Application = ApplicationProvider.getApplicationContext()
            AndroidMockRuleStore(application).save(emptyList())
            val provider = PlatformFacadeProvider()
            provider.initialize(application, DevConsoleConfig.default())
            provider.mockEngine().upsert(MockRule("session-stop-test", 2, scope = MockScope.SESSION))
            provider
                .mockEngine()
                .upsert(MockRule("durable-stop-test", 1, scope = MockScope.PERSISTENT_INTERNAL))

            provider.stop(StopReason.UserRequested)

            assertTrue(provider.mockEngine().isEnabled())
            assertEquals(listOf("durable-stop-test"), provider.mockEngine().rules().map(MockRule::id))
            assertTrue(provider.mockEngine().remove("durable-stop-test"))
        }

    @Test
    fun `explicit mock kill switch remains disabled across server restart`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8160..8179))
            provider.mockEngine().setEnabled(false)

            provider.stop(StopReason.UserRequested)
            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8180..8199))

            assertTrue(!provider.mockEngine().isEnabled())
            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `full facade can start a LAN-bound server on a device below the permission floor`() =
        runTest {
            val provider = PlatformFacadeProvider()

            assertEquals(
                InitResult.Initialized,
                provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default()),
            )
            val started = provider.startBrowser(StartRequest(BindingMode.LAN, 8180..8199))

            assertTrue(started is StartResult.Started)
            started as StartResult.Started
            assertEquals(BindingMode.LAN, started.endpoint.bindingMode)
            assertNotEquals("127.0.0.1", started.endpoint.host)
            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `capability accessors are usable and a config-registered state provider does not crash init`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val flag = FeatureFlag(key = "sample.enabled", defaultValue = true)
            val provided = stateProvider("sample") { StateSnapshot(emptyMap()) }

            assertEquals(
                InitResult.Initialized,
                provider.initialize(
                    ApplicationProvider.getApplicationContext(),
                    DevConsoleConfig(stateProviders = listOf(provided), featureFlags = listOf(flag)),
                ),
            )

            provider.networkRecorder().record(
                request = NetworkRequestInput("GET", "https://api.test/orders?access_token=secret"),
                response = null,
                startedAtEpochMs = 0,
                completedAtEpochMs = 0,
            )
            val event = provider.recordPush(PushInput("fcm", mapOf("access_token" to "raw")))

            assertNotEquals("raw", event.data.getValue("access_token"))
            assertEquals("<redacted>", event.data.getValue("access_token"))
        }

    @Test
    fun `featureFlagStringValue reads a multi-valued flag's default and an unknown key is empty`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val environment =
                FeatureFlag.ofOptions("env", defaultValue = "staging", options = setOf("staging", "production"))

            provider.initialize(
                ApplicationProvider.getApplicationContext(),
                DevConsoleConfig(featureFlags = listOf(environment)),
            )

            assertEquals("staging", provider.featureFlagStringValue("env"))
            assertEquals("", provider.featureFlagStringValue("no-such-flag"))
        }

    @Test
    fun `featureFlagValue reads a boolean flag's default and an unknown key is false, not a crash`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val flag = FeatureFlag(key = "new_ui", defaultValue = true)

            provider.initialize(
                ApplicationProvider.getApplicationContext(),
                DevConsoleConfig(featureFlags = listOf(flag)),
            )

            assertEquals(true, provider.featureFlagValue("new_ui"))
            assertEquals(false, provider.featureFlagValue("no-such-flag"))
        }

    @Test
    fun `a browser can exchange the session code over HTTP with no approval round trip`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            val started = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8280..8299)) as StartResult.Started

            val accessToken = exchangeSessionCodeOverHttp(started, "Test Browser")

            assertNotEquals(null, accessToken)
            assertEquals(HttpURLConnection.HTTP_OK, sessionStatusOverHttp(started, accessToken!!))

            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `stopping the console revokes browser sessions`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            val started = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8360..8379)) as StartResult.Started

            val accessToken = exchangeSessionCodeOverHttp(started, "Browser To Stop")
            assertNotEquals(null, accessToken)
            assertEquals(HttpURLConnection.HTTP_OK, sessionStatusOverHttp(started, accessToken!!))

            provider.stop(StopReason.UserRequested)

            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            val restarted = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8380..8399)) as StartResult.Started
            // The old session does not survive a stop/restart -- a fresh authority means a fresh store.
            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, sessionStatusOverHttp(restarted, accessToken))

            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `concurrent initialize and start does not throw UninitializedPropertyAccessException`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val application = ApplicationProvider.getApplicationContext<Application>()
            val config = DevConsoleConfig.default()

            val threadCount = 4
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(threadCount)
            val readyLatch = java.util.concurrent.CountDownLatch(threadCount)
            val startLatch = java.util.concurrent.CountDownLatch(1)
            val doneLatch = java.util.concurrent.CountDownLatch(threadCount)
            val errors = mutableListOf<Throwable>()

            repeat(threadCount) { index ->
                executor.execute {
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        if (index % 2 == 0) {
                            provider.initialize(application, config)
                        } else {
                            kotlinx.coroutines.runBlocking {
                                provider.startBrowser(StartRequest(portRange = 8340..8359))
                            }
                        }
                    } catch (t: Throwable) {
                        synchronized(errors) { errors.add(t) }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            startLatch.countDown()
            assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            executor.shutdown()

            assertTrue(
                "Expected no UninitializedPropertyAccessException, got: $errors",
                errors.none { it is UninitializedPropertyAccessException },
            )
            provider.stop(StopReason.UserRequested)
        }

    /**
     * SESSION_CODE is the only browser-access flow: `started.access.sessionCode` carries the
     * 8-char session code -- see `PlatformFacadeProvider.mapStartResult`. Exchanging it mints a
     * session immediately, with no on-device approval step. Returns the `accessToken`, or null if
     * the exchange did not succeed.
     */
    private fun exchangeSessionCodeOverHttp(
        started: StartResult.Started,
        browserLabel: String,
    ): String? {
        val url = URL("http://${started.endpoint.host}:${started.endpoint.port}/api/v1/auth/session-code/exchange")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            val encodedLabel = java.net.URLEncoder.encode(browserLabel, "UTF-8")
            val encodedCode = java.net.URLEncoder.encode(started.access.sessionCode, "UTF-8")
            connection.outputStream.use { it.write("code=$encodedCode&browserLabel=$encodedLabel".toByteArray()) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().readText()
            Regex("\"accessToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        } finally {
            connection.disconnect()
        }
    }

    private fun sessionStatusOverHttp(
        started: StartResult.Started,
        accessToken: String,
    ): Int {
        val url = URL("http://${started.endpoint.host}:${started.endpoint.port}/api/v1/session")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `endpoint and access info are readable after start and cleared on stop`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            val started = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8420..8439)) as StartResult.Started

            assertEquals(started.endpoint, provider.endpoint())
            assertEquals(started.access.connectUrl, provider.accessInfo()?.connectUrl)

            provider.stop(StopReason.UserRequested)

            assertNull(provider.endpoint())
            assertNull(provider.accessInfo())
        }

    // A session code has its own (shorter) TTL than the server's lifetime -- once it expires
    // while the server keeps running, accessInfo() must re-issue automatically (reusing the real
    // bind address) rather than keep advertising a dead, already-expired code.
    @Test
    fun `access info re-issues a fresh session code once the old one expires while running`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(
                ApplicationProvider.getApplicationContext(),
                DevConsoleConfig.default().withBrowserConfig(io.devconsole.api.BrowserConfig(sessionCodeTtlMs = 20)),
            )
            val started = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8560..8579)) as StartResult.Started

            Thread.sleep(40)
            val afterExpiry = provider.accessInfo()

            assertTrue("expected a re-issued, still-connectable code", afterExpiry != null)
            assertNotEquals(started.access.sessionCode, afterExpiry!!.sessionCode)
            assertTrue(afterExpiry.connectUrl.contains(started.endpoint.host))
            assertEquals(started.endpoint.port, provider.endpoint()?.port)
        }

    @Test
    fun `More surface's session-code URL is readable while running and cleared on stop`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8500..8519)) as StartResult.Started

            val browser = DevConsoleInspectorBridge.source().snapshot().browser
            assertTrue(
                "expected a #code= session-code URL, got ${browser?.sessionCodeUrl}",
                browser?.sessionCodeUrl?.contains("#code=") == true,
            )

            provider.stop(StopReason.UserRequested)

            val afterStop = DevConsoleInspectorBridge.source().snapshot().browser
            assertNull(
                "session-code URL must disappear once the server (and its code) stops",
                afterStop?.sessionCodeUrl,
            )
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `running state never becomes visible before its endpoint`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            var endpointObservedWithRunning: BrowserEndpoint? = null
            val observer =
                backgroundScope.launch(
                    context = UnconfinedTestDispatcher(testScheduler),
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    provider.state().collect { state ->
                        if (state is DevConsoleState.Running) {
                            endpointObservedWithRunning = provider.endpoint()
                        }
                    }
                }

            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8480..8499))

            assertTrue("Running observers must be able to resolve the endpoint", endpointObservedWithRunning != null)
            observer.cancel()
            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `the server can be restarted after stop without re-initializing`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())
            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8440..8459))
            provider.stop(StopReason.UserRequested)

            val restarted = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8460..8479))

            assertTrue("expected a restart to succeed, got $restarted", restarted is StartResult.Started)
            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `same config reinitialize cannot orphan the live server`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val application = ApplicationProvider.getApplicationContext<Application>()
            val config = DevConsoleConfig.default()
            val port = java.net.ServerSocket(0).use { it.localPort }

            assertEquals(InitResult.Initialized, provider.initialize(application, config))
            assertTrue(provider.startBrowser(StartRequest(BindingMode.LOOPBACK, port..port)) is StartResult.Started)

            assertEquals(InitResult.ExistingRuntime, provider.initialize(application, config))
            provider.stop(StopReason.UserRequested)

            val restarted = provider.startBrowser(StartRequest(BindingMode.LOOPBACK, port..port))
            assertTrue(
                "expected the same listener owner to release port $port, got $restarted",
                restarted is StartResult.Started,
            )
            provider.stop(StopReason.UserRequested)
        }
}
