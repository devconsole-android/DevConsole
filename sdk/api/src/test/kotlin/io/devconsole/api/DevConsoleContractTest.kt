package io.devconsole.api

import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleContractTest {
    @Test
    fun `FR-CORE-006 default config accepts a positive event buffer`() {
        assertEquals(256, DevConsoleConfig.default().eventBufferCapacity)
    }

    @Test
    fun `FR-CORE-006 validation aggregates every invalid configuration before startup`() {
        val config =
            DevConsoleConfig(
                eventBufferCapacity = 0,
                redactionPolicy =
                    RedactionPolicy(
                        sensitiveFieldNames = setOf("token"),
                        textPatterns = listOf(Regex("(?=unsafe)")),
                    ),
            ).withStoragePolicy(
                StoragePolicy(
                    maxBytes = 0,
                    maxAgeMs = 0,
                    maxTimelineEvents = 0,
                    maxNetworkTransactions = 0,
                ),
            ).withSessionPolicy(
                SessionPolicy(maxAuthenticatedSessions = 0),
            )

        val codes = config.validationErrors(isDebuggableRuntime = false).map { it.code }.toSet()

        assertTrue(ConfigValidationCode.INVALID_EVENT_BUFFER_CAPACITY in codes)
        assertTrue(ConfigValidationCode.INVALID_REDACTION_EXPRESSION in codes)
        assertTrue(ConfigValidationCode.INVALID_STORAGE_POLICY in codes)
        assertTrue(ConfigValidationCode.INVALID_SESSION_POLICY in codes)
        assertTrue(ConfigValidationCode.UNSAFE_PRODUCTION_RUNTIME in codes)
    }

    @Test
    fun `StartRequest reports an invalid port range independently of config validation`() {
        val errors = StartRequest(portRange = 0..70_000).validationErrors()

        assertEquals(listOf(ConfigValidationCode.INVALID_PORT_RANGE), errors.map { it.code })
    }

    @Test
    fun `default configuration is valid and uses documented session and storage limits`() {
        val config = DevConsoleConfig.default()

        assertTrue(config.validationErrors().isEmpty())
        assertEquals(10, config.sessionPolicy.maxAuthenticatedSessions)
        assertEquals(100L * 1024L * 1024L, config.storagePolicy.maxBytes)
        assertEquals(24L * 60L * 60L * 1000L, config.storagePolicy.maxAgeMs)
    }

    @Test
    fun `FR-TIME-001 envelope preserves documented schema version`() {
        assertEquals(1, EventEnvelope.CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `FR-CORE-003 exposes every required state`() {
        val requiredSingletonStates =
            setOf(
                DevConsoleState.Uninitialized,
                DevConsoleState.DisabledForBuild,
                DevConsoleState.Initialized,
                DevConsoleState.PermissionRequired,
                DevConsoleState.Starting,
                DevConsoleState.Running,
                DevConsoleState.Stopping,
                DevConsoleState.Stopped,
            )

        assertTrue(
            "actual=${DevConsoleState.all}; required=$requiredSingletonStates",
            DevConsoleState.all.containsAll(requiredSingletonStates),
        )
        assertTrue(DevConsoleState.all.any { it is DevConsoleState.Failed })
    }

    @Test
    fun `AccessInfo toString never leaks the session code or connect URL`() {
        val access =
            AccessInfo(
                connectUrl = "http://127.0.0.1:8080/#code=ABCD2345",
                sessionCode = "ABCD2345",
                expiresAtEpochMs = 1_000L,
            )

        val rendered = access.toString()

        assertFalse(rendered.contains("ABCD2345"))
        assertFalse(rendered.contains("#code="))
        assertEquals("AccessInfo(expiresAtEpochMs=1000)", rendered)
    }

    @Test
    fun `StartResult Started carries a BrowserEndpoint and AccessInfo`() {
        val started =
            StartResult.Started(
                endpoint = BrowserEndpoint(host = "127.0.0.1", port = 8080, bindingMode = BindingMode.LOOPBACK),
                access =
                    AccessInfo(
                        connectUrl = "http://127.0.0.1:8080/#code=ABCD2345",
                        sessionCode = "ABCD2345",
                        expiresAtEpochMs = 1_000L,
                    ),
            )

        assertEquals(BindingMode.LOOPBACK, started.endpoint.bindingMode)
        assertEquals(8080, started.endpoint.port)
        assertEquals("ABCD2345", started.access.sessionCode)
        assertTrue(started.access.connectUrl.contains("#code=ABCD2345"))
    }
}
