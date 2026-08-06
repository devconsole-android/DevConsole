/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import io.devconsole.api.CaptureCategory
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.EditingCapabilities
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketConnectionState
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.state.FeatureFlag
import io.devconsole.state.SessionFeatureFlags
import io.devconsole.state.StateRegistry
import io.devconsole.state.StateSnapshot
import io.devconsole.state.StateValue
import io.devconsole.state.stateProvider
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FullInspectorDataSource.snapshot] gating by [DevConsoleConfig.captureCategories] (Feature 2).
 * Every disabled category must empty its own field(s) and force the matching editing flag off; a
 * default config (every category enabled) must leave every list exactly as before this feature.
 */
class CaptureCategoryGatingTest {
    private fun networkStore(): InMemoryNetworkTransactionStore {
        val store =
            InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key-1234567890".encodeToByteArray()))
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput(method = "GET", url = "https://api.example.test/orders"),
                NetworkResponseInput(statusCode = 200),
            )
        store.record(NetworkTransaction("tx-1", 0, 5, capture))
        return store
    }

    private fun socketStoreWithBothProtocols(): InMemorySocketStore {
        val store = InMemorySocketStore()
        store.open(
            SocketConnection(
                id = "ws-1",
                url = "wss://api.example.test/stream",
                openedAtEpochMs = 100,
                state = SocketConnectionState.OPEN,
                protocol = SocketProtocol.WEBSOCKET,
            ),
        )
        store.open(
            SocketConnection(
                id = "mqtt-1",
                url = "tcp://broker.example.test:1883",
                openedAtEpochMs = 100,
                state = SocketConnectionState.OPEN,
                protocol = SocketProtocol.MQTT,
            ),
        )
        return store
    }

    private fun pushStoreWithOneEvent(): InMemoryPushStore {
        val store = InMemoryPushStore()
        store.append(PushEvent(provider = "fcm", data = emptyMap(), messageId = "msg-1"))
        return store
    }

    private fun timelineWithLogAndCrash(): InMemoryTimeline {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key-1234567890".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "log-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "logs",
                type = "log",
                wallTimeMs = 10,
                monoTimeNs = 10,
                severity = 2,
                summary = "hello",
                tagsJson = """{"tag":"App","level":"INFO"}""",
            ),
        )
        timeline.append(
            StoredEvent(
                id = "crash-1",
                sessionId = "session-1",
                sequence = 2,
                pluginId = "crash",
                type = "uncaught",
                wallTimeMs = 20,
                monoTimeNs = 20,
                severity = 4,
                summary = "boom",
                tagsJson = """{"kind":"UNCAUGHT","thread":"main"}""",
                payloadJson = """{"stackTrace":"at Foo.bar"}""",
            ),
        )
        return timeline
    }

    private fun stateRegistryWithProvider(): StateRegistry {
        val registry = StateRegistry()
        registry.register(
            stateProvider("prefs") { StateSnapshot(values = mapOf("theme" to StateValue.StringValue("dark"))) },
        )
        return registry
    }

    private fun featureFlagsWithOneFlag(): SessionFeatureFlags =
        SessionFeatureFlags(listOf(FeatureFlag("dark_mode", false)))

    private class FakePreferencesInspector : PreferencesInspector {
        override fun files(): List<PreferencesFileData> =
            listOf(PreferencesFileData(name = "prefs", entries = emptyList()))

        override fun put(
            file: String,
            key: String,
            value: String,
            type: String,
        ): Boolean = true

        override fun remove(
            file: String,
            key: String,
        ): Boolean = true
    }

    private fun fullyEnabledConfig(): DevConsoleConfig =
        DevConsoleConfig
            .default()
            .withEditingCapabilities(
                EditingCapabilities
                    .builder()
                    .mocks(true)
                    .featureFlags(true)
                    .preferences(true)
                    .files(true)
                    .database(true)
                    .captureRules(true)
                    .build(),
            )

    private fun buildSource(
        config: DevConsoleConfig,
        mockEngine: MockEngine =
            MockEngine(
                listOf(
                    MockRule(
                        id = "rule-1",
                        priority = 0,
                        path = "/orders",
                        action = MockAction.StaticResponse(200, "{}"),
                    ),
                ),
            ),
    ): FullInspectorDataSource =
        FullInspectorDataSource(
            networkStore(),
            mockEngine,
            configSupplier = { config },
            socketStore = socketStoreWithBothProtocols(),
            pushStore = pushStoreWithOneEvent(),
            timelineSupplier = { timelineWithLogAndCrash() },
            featureFlagsSupplier = { featureFlagsWithOneFlag() },
            stateRegistry = stateRegistryWithProvider(),
            preferencesInspector = FakePreferencesInspector(),
            fileInspector = null,
            databaseInspector = null,
        )

    @Test
    fun `default config -- every category enabled -- leaves every list populated`() {
        val source = buildSource(fullyEnabledConfig())

        val snapshot = source.snapshot()

        assertEquals(CaptureCategory.all(), snapshot.captureCategories)
        assertTrue(snapshot.transactions.isNotEmpty())
        assertEquals(2, snapshot.sockets.size)
        assertTrue(snapshot.pushEvents.isNotEmpty())
        assertTrue(snapshot.logs.isNotEmpty())
        assertTrue(snapshot.crashes.isNotEmpty())
        assertTrue(snapshot.featureFlags.isNotEmpty())
        assertTrue(snapshot.stateProviders.isNotEmpty())
        assertTrue(snapshot.preferenceFiles.isNotEmpty())
        assertTrue(snapshot.mockRules.isNotEmpty())
    }

    @Test
    fun `only SOCKET and MQTT enabled -- every other list is empty and editing flags are false`() {
        val config = fullyEnabledConfig().withCaptureCategories(CaptureCategory.SOCKET, CaptureCategory.MQTT)
        val source = buildSource(config)

        val snapshot = source.snapshot()

        assertEquals(setOf(CaptureCategory.SOCKET, CaptureCategory.MQTT), snapshot.captureCategories)
        assertTrue(snapshot.transactions.isEmpty())
        assertEquals(2, snapshot.sockets.size)
        assertTrue(snapshot.pushEvents.isEmpty())
        assertTrue(snapshot.logs.isEmpty())
        assertTrue(snapshot.crashes.isEmpty())
        assertTrue(snapshot.featureFlags.isEmpty())
        assertTrue(snapshot.stateProviders.isEmpty())
        assertTrue(snapshot.preferenceFiles.isEmpty())
        assertTrue(snapshot.fileRoots.isEmpty())
        assertTrue(snapshot.databases.isEmpty())
        assertTrue(snapshot.mockRules.isEmpty())
        assertTrue(snapshot.captureRules.isEmpty())

        assertFalse(snapshot.capabilities.mocks)
        assertFalse(snapshot.capabilities.featureFlags)
        assertFalse(snapshot.capabilities.preferences)
        assertFalse(snapshot.capabilities.files)
        assertFalse(snapshot.capabilities.database)
        assertFalse(snapshot.capabilities.captureRules)
    }

    @Test
    fun `socket filtering drops MQTT connections when MQTT is off`() {
        val config = fullyEnabledConfig().withCaptureCategories(CaptureCategory.SOCKET)
        val source = buildSource(config)

        val sockets = source.snapshot().sockets

        assertEquals(listOf("ws-1"), sockets.map { it.id })
        assertEquals("websocket", sockets.single().protocol)
    }

    @Test
    fun `socket filtering drops WebSocket connections when SOCKET is off`() {
        val config = fullyEnabledConfig().withCaptureCategories(CaptureCategory.MQTT)
        val source = buildSource(config)

        val sockets = source.snapshot().sockets

        assertEquals(listOf("mqtt-1"), sockets.map { it.id })
        assertEquals("mqtt", sockets.single().protocol)
    }

    @Test
    fun `empty capture category set empties every socket too`() {
        val config = fullyEnabledConfig().withCaptureCategories(CaptureCategory.none())
        val source = buildSource(config)

        assertTrue(source.snapshot().sockets.isEmpty())
    }

    @Test
    fun `absent config is fail-open and behaves like every category enabled`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                socketStore = socketStoreWithBothProtocols(),
                pushStore = pushStoreWithOneEvent(),
            )

        val snapshot = source.snapshot()

        assertEquals(CaptureCategory.all(), snapshot.captureCategories)
        assertTrue(snapshot.transactions.isNotEmpty())
        assertEquals(2, snapshot.sockets.size)
        assertTrue(snapshot.pushEvents.isNotEmpty())
    }

    @Test
    fun `MQTT frame metadata surfaces topic and qos on the socket frame UI`() {
        val socketStore = InMemorySocketStore()
        socketStore.open(
            SocketConnection(
                id = "mqtt-1",
                url = "tcp://broker.example.test:1883",
                openedAtEpochMs = 100,
                protocol = SocketProtocol.MQTT,
            ),
        )
        socketStore.append(
            SocketMessage(
                connectionId = "mqtt-1",
                direction = SocketDirection.RECEIVED,
                timestampEpochMs = 110,
                payload = SocketPayload.Text("hi"),
                contentType =
                    io.devconsole.socket.MqttFrameMetadata.format(
                        "devconsole/demo",
                        qos = 1,
                        retained = true,
                    ),
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { fullyEnabledConfig() },
                socketStore = socketStore,
            )

        val frame =
            source
                .snapshot()
                .sockets
                .single()
                .frames
                .single()

        assertEquals("devconsole/demo", frame.topic)
        assertEquals(1, frame.qos)
    }
}
