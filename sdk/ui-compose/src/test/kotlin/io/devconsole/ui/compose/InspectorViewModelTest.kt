/**
 * @author Shakib
 * @since 24/07/26
 */
package io.devconsole.ui.compose

import io.devconsole.api.CaptureCategory
import io.devconsole.api.ScreenshotResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("LargeClass") // Exhaustive per-action MVI coverage; mirrors FullInspectorDataSourceTest's justification.
class InspectorViewModelTest {
    @Test
    fun `initial load populates state from snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transaction = sampleTransaction()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        transactions = listOf(transaction),
                        capabilities = InspectorEditingUi(requestExecution = true, mocks = true),
                        mocksEnabled = true,
                        mockRules = listOf(InspectorMockRuleUi("rule-1", "GET", "/orders/*", "Return 500")),
                    ),
                )

            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.available)
            assertEquals(listOf(transaction), state.transactions)
            assertTrue(state.capabilities.requestExecution)
            assertTrue(state.capabilities.mocks)
            assertTrue(state.mocksEnabled)
            assertEquals(1, state.mockRules.size)
        }

    @Test
    fun `refresh re-reads the latest snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            val initialTransactions = viewModel.state.value.transactions
            assertTrue(initialTransactions.isEmpty())

            val newTransaction = sampleTransaction(id = "tx-refreshed")
            source.snapshotToReturn = InspectorSnapshot(available = true, transactions = listOf(newTransaction))
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(newTransaction), viewModel.state.value.transactions)
        }

    @Test
    fun `empty transactions produce an empty state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = emptyList()))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.transactions.isEmpty())
        }

    @Test
    fun `an unavailable source produces an unavailable state instead of crashing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot())
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.available)
            assertTrue(state.transactions.isEmpty())
            assertFalse(state.capabilities.requestExecution)
            assertFalse(state.capabilities.mocks)
        }

    @Test
    fun `the visible transaction list is bounded to the most recent entries`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val manyTransactions = (1..250).map { sampleTransaction(id = "tx-$it") }
            val snapshot = InspectorSnapshot(available = true, transactions = manyTransactions)
            val source = RecordingInspectorDataSource(snapshot)
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val visible = viewModel.state.value.transactions
            assertEquals(200, visible.size)
            assertEquals("tx-250", visible.last().id)
        }

    @Test
    fun `executing a request is a no-op when request execution is disabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        capabilities = InspectorEditingUi(requestExecution = false, mocks = false),
                    ),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExecuteComposer(sampleComposerRequest()))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.executeCallCount)
            assertEquals(InspectorCommandResult.Disabled("requestExecution"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `executing a request calls through and stores the result when enabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        capabilities = InspectorEditingUi(requestExecution = true, mocks = false),
                    ),
                )
            source.executeResult = InspectorCommandResult.Success(summary = "OK", statusCode = 200, body = "{}")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val request = sampleComposerRequest()
            viewModel.dispatch(InspectorAction.ExecuteComposer(request))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.executeCallCount)
            assertEquals(request, source.lastExecuteRequest)
            assertEquals(source.executeResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `toggling mocks is a no-op when mocks capability is disabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        capabilities = InspectorEditingUi(requestExecution = false, mocks = false),
                    ),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SetMocksEnabled(true))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.setMocksEnabledCallCount)
            assertEquals(InspectorCommandResult.Disabled("mocks"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `toggling mocks calls through and stores the result when enabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        capabilities = InspectorEditingUi(requestExecution = false, mocks = true),
                    ),
                )
            source.setMocksResult = InspectorCommandResult.Success(summary = "Mocks enabled")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SetMocksEnabled(true))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.setMocksEnabledCallCount)
            assertEquals(true, source.lastMocksEnabledValue)
            assertEquals(source.setMocksResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `initial load populates sockets, push events, and logs from snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val socket = sampleSocket()
            val push = samplePush()
            val log = sampleLog()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        sockets = listOf(socket),
                        pushEvents = listOf(push),
                        logs = listOf(log),
                    ),
                )

            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf(socket), state.sockets)
            assertEquals(listOf(push), state.pushEvents)
            assertEquals(listOf(log), state.logs)
        }

    @Test
    fun `refresh re-reads sockets, push events, and logs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                viewModel.state.value.sockets
                    .isEmpty(),
            )

            val socket = sampleSocket(id = "socket-refreshed")
            source.snapshotToReturn = InspectorSnapshot(available = true, sockets = listOf(socket))
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(socket), viewModel.state.value.sockets)
        }

    @Test
    fun `empty snapshot yields empty sockets, push events, and logs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.sockets.isEmpty())
            assertTrue(state.pushEvents.isEmpty())
            assertTrue(state.logs.isEmpty())
        }

    @Test
    fun `initial load populates feature flags and state providers from snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val flag = sampleFeatureFlag()
            val provider = sampleStateProvider()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        featureFlags = listOf(flag),
                        stateProviders = listOf(provider),
                    ),
                )

            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf(flag), state.featureFlags)
            assertEquals(listOf(provider), state.stateProviders)
        }

    @Test
    fun `setting a feature flag is a no-op when featureFlags capability is disabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        capabilities = InspectorEditingUi(featureFlags = false),
                        featureFlags = listOf(sampleFeatureFlag()),
                    ),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SetFeatureFlag("dark_mode", "true"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.setFeatureFlagCallCount)
            assertEquals(InspectorCommandResult.Disabled("featureFlags"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `setting a feature flag calls through and stores the result when enabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        capabilities = InspectorEditingUi(featureFlags = true),
                        featureFlags = listOf(sampleFeatureFlag()),
                    ),
                )
            source.setFeatureFlagResult = InspectorCommandResult.Success(summary = "Flag updated")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SetFeatureFlag("dark_mode", "true"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.setFeatureFlagCallCount)
            assertEquals("dark_mode", source.lastFeatureFlagKey)
            assertEquals("true", source.lastFeatureFlagValue)
            assertEquals(source.setFeatureFlagResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `setting a preference is a no-op when the preferences capability is off`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(preferences = false)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SetPreference("user_prefs", "theme", "light", "STRING"))
            viewModel.dispatch(InspectorAction.RemovePreference("user_prefs", "theme"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.setPreferenceCallCount)
            assertEquals(0, source.removePreferenceCallCount)
            assertEquals(InspectorCommandResult.Disabled("preferences"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `setting a preference calls through and stores the result when enabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(preferences = true)),
                )
            source.setPreferenceResult = InspectorCommandResult.Success(summary = "Preference set")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SetPreference("user_prefs", "theme", "light", "STRING"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.setPreferenceCallCount)
            assertEquals("theme", source.lastPreferenceKey)
            assertEquals("light", source.lastPreferenceValue)
            assertEquals(source.setPreferenceResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `opening a file path loads a listing without needing any capability`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.listingToReturn =
                InspectorFileListingUi(root = "files", relativePath = "logs", entries = emptyList())
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.OpenFilePath("files", "logs"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "logs",
                viewModel.state.value.fileListing
                    ?.relativePath,
            )
            assertEquals("logs", source.lastListedPath)
        }

    @Test
    fun `deleting a file is a no-op when the files capability is off`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(files = false)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.DeleteFile("files", "logs/today.log"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.deleteFileCallCount)
            assertEquals(InspectorCommandResult.Disabled("files"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `deleting a file calls through when the files capability is on`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(files = true)),
                )
            source.deleteFileResult = InspectorCommandResult.Success(summary = "File deleted")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.DeleteFile("files", "logs/today.log"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.deleteFileCallCount)
            assertEquals(source.deleteFileResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `sharing a file is a no-op when the files capability is off`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(files = false)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ShareFile("files", "logs/today.log"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.shareableFilePathCallCount)
            assertEquals(InspectorCommandResult.Disabled("files"), viewModel.state.value.lastCommandResult)
            assertNull(viewModel.state.value.pendingShareFilePath)
        }

    @Test
    fun `sharing a file stores the resolved path when the files capability is on`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(files = true)),
                )
            source.shareableFilePathResult = "/data/user/0/app/files/logs/today.log"
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ShareFile("files", "logs/today.log"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.shareableFilePathCallCount)
            assertEquals("/data/user/0/app/files/logs/today.log", viewModel.state.value.pendingShareFilePath)
        }

    @Test
    fun `sharing a file reports Invalid when the data source cannot resolve a path`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(files = true)),
                )
            source.shareableFilePathResult = null
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ShareFile("files", "logs/today.log"))
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.state.value.lastCommandResult is InspectorCommandResult.Invalid)
            assertNull(viewModel.state.value.pendingShareFilePath)
        }

    @Test
    fun `consuming the pending share path clears it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(files = true)),
                )
            source.shareableFilePathResult = "/data/user/0/app/files/a.txt"
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ShareFile("files", "a.txt"))
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ConsumeShareFile)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.pendingShareFilePath)
        }

    @Test
    fun `opening a database loads its table listing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.tablesToReturn =
                InspectorDatabaseListingUi(
                    name = "demo.db",
                    tables = listOf(InspectorDatabaseTableUi("users", 1)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.OpenDatabase("demo.db"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "demo.db",
                viewModel.state.value.databaseListing
                    ?.name,
            )
            assertEquals(
                1,
                viewModel.state.value.databaseListing
                    ?.tables
                    ?.size,
            )
        }

    @Test
    fun `opening a table loads the query result`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.queryToReturn =
                InspectorQueryResultUi(columns = listOf("id"), rows = listOf(listOf("1")), truncated = false)
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.OpenTable("demo.db", "users"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                listOf("id"),
                viewModel.state.value.queryResult
                    ?.columns,
            )
            assertEquals(
                listOf(listOf("1")),
                viewModel.state.value.queryResult
                    ?.rows,
            )
        }

    @Test
    fun `executing SQL calls through and stores the result`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.sqlResult = InspectorSqlResultUi.Wrote(affectedRows = 3)
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExecuteSql("demo.db", "DELETE FROM users"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.executeSqlCallCount)
            assertEquals("DELETE FROM users", source.lastSql)
            assertEquals(source.sqlResult, viewModel.state.value.sqlResult)
        }

    @Test
    fun `executing SQL calls through even when the database capability is disabled, since the engine gates writes`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(database = false)),
                )
            source.sqlResult = InspectorSqlResultUi.WriteBlocked
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExecuteSql("demo.db", "DELETE FROM users"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.executeSqlCallCount)
            assertEquals(InspectorSqlResultUi.WriteBlocked, viewModel.state.value.sqlResult)
        }

    @Test
    fun `initial load populates sessions, health, and browser from snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = sampleSession()
            val health = sampleHealth()
            val browser = sampleBrowser()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, sessions = listOf(session), health = health, browser = browser),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf(session), state.sessions)
            assertEquals(health, state.health)
            assertEquals(browser, state.browser)
        }

    @Test
    fun `exporting HAR calls through and stores the result`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.exportHarResult = InspectorCommandResult.Success(summary = "Saved to /data/devconsole-exports/x.har")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExportHar)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.exportHarCallCount)
            assertEquals(emptySet<String>(), source.lastExportHarSelection)
            assertEquals(source.exportHarResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `exporting Postman calls through and stores the result`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.exportPostmanResult = InspectorCommandResult.Failed("disk full")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExportPostman)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.exportPostmanCallCount)
            assertEquals(source.exportPostmanResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `exporting HAR and Postman forward the traffic screen's transaction selection`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transactions = listOf(sampleTransaction("tx-1"), sampleTransaction("tx-2"))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = transactions))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-1"))

            viewModel.dispatch(InspectorAction.ExportHar)
            viewModel.dispatch(InspectorAction.ExportPostman)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("tx-1"), source.lastExportHarSelection)
            assertEquals(setOf("tx-1"), source.lastExportPostmanSelection)
        }

    @Test
    fun `exporting a session ZIP calls through and opens the share sheet on success`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.exportSessionZipResult =
                InspectorCommandResult.Success(summary = "Saved to /data/x.zip", sharePath = "/data/x.zip")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExportSessionZip)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.exportSessionZipCallCount)
            assertEquals(source.exportSessionZipResult, viewModel.state.value.lastCommandResult)
            assertEquals("/data/x.zip", viewModel.state.value.pendingShareFilePath)
        }

    @Test
    fun `a failed export does not open the share sheet`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.exportHarResult = InspectorCommandResult.Failed("disk full")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ExportHar)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.pendingShareFilePath)
        }

    @Test
    fun `initial load populates retention and browser principals from snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val retention = sampleRetention()
            val browser = sampleBrowser(principals = listOf(samplePrincipal()))
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, retention = retention, browser = browser),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(retention, state.retention)
            assertEquals(listOf(samplePrincipal()), state.browser?.principals)
        }

    @Test
    fun `revoking a principal calls through and refreshes the snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val withPrincipal = sampleBrowser(principals = listOf(samplePrincipal()))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, browser = withPrincipal))
            source.revokePrincipalResult = InspectorCommandResult.Success(summary = "Browser browser-1 revoked")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val withoutPrincipal = sampleBrowser(principals = emptyList())
            source.snapshotToReturn = InspectorSnapshot(available = true, browser = withoutPrincipal)
            viewModel.dispatch(InspectorAction.RevokePrincipal("browser-1"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.revokePrincipalCallCount)
            assertEquals("browser-1", source.lastRevokedPrincipalId)
            assertEquals(source.revokePrincipalResult, viewModel.state.value.lastCommandResult)
            val remainingPrincipals =
                viewModel.state.value.browser
                    ?.principals
                    .orEmpty()
            assertTrue(remainingPrincipals.isEmpty())
        }

    @Test
    fun `selecting an observe tab switches the destination without touching other state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(ObserveTab.TRAFFIC, viewModel.state.value.observeTab)

            viewModel.dispatch(InspectorAction.SelectObserveTab(ObserveTab.SOCKETS))

            assertEquals(ObserveTab.SOCKETS, viewModel.state.value.observeTab)
        }

    @Test
    fun `toggling a transaction selection twice returns it to unselected`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transactions = listOf(sampleTransaction("tx-1"), sampleTransaction("tx-2"))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = transactions))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-1"))
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-2"))
            assertEquals(setOf("tx-1", "tx-2"), viewModel.state.value.selectedTransactionIds)

            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-1"))
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-2"))

            assertTrue(
                viewModel.state.value.selectedTransactionIds
                    .isEmpty(),
            )
        }

    @Test
    fun `selecting transactions unions ids into the selection without dropping an existing one`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transactions = listOf(sampleTransaction("tx-1"), sampleTransaction("tx-2"), sampleTransaction("tx-3"))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = transactions))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-1"))

            viewModel.dispatch(InspectorAction.SelectTransactions(setOf("tx-2", "tx-3")))

            assertEquals(setOf("tx-1", "tx-2", "tx-3"), viewModel.state.value.selectedTransactionIds)
        }

    @Test
    fun `selecting all matching filter and exporting forwards exactly the filtered ids`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transactions = listOf(sampleTransaction("tx-1"), sampleTransaction("tx-2"), sampleTransaction("tx-3"))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = transactions))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            // Stands in for the Traffic tab's filtered id set -- gathering it from the active
            // search/chip filter is a @Composable concern (InspectorObserveTrafficTab.kt) this
            // device-free suite cannot exercise; what's testable here is that the ViewModel forwards
            // whatever id set it is given, exactly, to the export call.
            viewModel.dispatch(InspectorAction.SelectTransactions(setOf("tx-1", "tx-3")))
            viewModel.dispatch(InspectorAction.ExportHar)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("tx-1", "tx-3"), source.lastExportHarSelection)
        }

    @Test
    fun `clearing the transaction selection empties it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transactions = listOf(sampleTransaction("tx-1"), sampleTransaction("tx-2"))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = transactions))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-1"))
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-2"))
            assertEquals(setOf("tx-1", "tx-2"), viewModel.state.value.selectedTransactionIds)

            viewModel.dispatch(InspectorAction.ClearTransactionSelection)

            assertTrue(
                viewModel.state.value.selectedTransactionIds
                    .isEmpty(),
            )
        }

    @Test
    fun `clearing the selection then exporting exports everything, not the stale selection`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val transactions = listOf(sampleTransaction("tx-1"), sampleTransaction("tx-2"))
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, transactions = transactions))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ToggleTransactionSelection("tx-1"))
            viewModel.dispatch(InspectorAction.ClearTransactionSelection)

            viewModel.dispatch(InspectorAction.ExportPostman)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptySet<String>(), source.lastExportPostmanSelection)
        }

    @Test
    fun `selecting a session loads its logs and clears the loading flag`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = sampleSession()
            val log = sampleLog()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, sessions = listOf(session), logs = listOf(log)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SelectSession(session.id))

            assertEquals(session.id, viewModel.state.value.selectedSessionId)
            assertTrue(viewModel.state.value.sessionLogsLoading)
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.state.value.sessionLogsLoading)
            assertEquals(listOf(log), viewModel.state.value.logs)
        }

    @Test
    fun `deselecting a session clears selectedSessionId without loading`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(InspectorSnapshot(available = true, sessions = listOf(sampleSession())))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.SelectSession(sampleSession().id))
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SelectSession(null))
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.selectedSessionId)
            assertFalse(viewModel.state.value.sessionLogsLoading)
        }

    @Test
    fun `snapshot maps crashes and bounds them like every other visible list`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val crash = sampleCrash()
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, crashes = listOf(crash)))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(crash), viewModel.state.value.crashes)
        }

    @Test
    fun `selecting a session loads its crashes alongside its logs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = sampleSession()
            val crash = sampleCrash()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, sessions = listOf(session), crashes = listOf(crash)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SelectSession(session.id))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(crash), viewModel.state.value.crashes)
        }

    @Test
    fun `a live refresh while a past session is selected does not clobber its logs or crashes`() =
        runTest {
            // Regression test for the Crashes tab's previous-run banner: selecting a past
            // session and switching tabs in the same user action used to race a debounced live
            // refresh, which would silently snap `logs`/`crashes` back to the *live* session's data
            // moments after the operator navigated to the past one.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = sampleSession()
            val pastLog = sampleLog()
            val pastCrash = sampleCrash()
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(
                        available = true,
                        sessions = listOf(session),
                        logs = listOf(pastLog),
                        crashes = listOf(pastCrash),
                    ),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.SelectSession(session.id))
            dispatcher.scheduler.advanceUntilIdle()
            // The live snapshot moves on (a new live event lands) while the past session stays selected.
            source.snapshotToReturn =
                source.snapshotToReturn.copy(
                    logs = listOf(sampleLog().copy(id = "live-log")),
                    crashes = listOf(sampleCrash().copy(id = "live-crash")),
                )
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(pastLog), viewModel.state.value.logs)
            assertEquals(listOf(pastCrash), viewModel.state.value.crashes)
        }

    @Test
    fun `capturing a screenshot records the result and refreshes on success`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.captureScreenshotResult =
                ScreenshotResult.Captured("attachment-1", "event-1", widthPx = 100, heightPx = 200, byteCount = 4_096)
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.CaptureScreenshot)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.captureScreenshotCallCount)
            assertEquals(source.captureScreenshotResult, viewModel.state.value.lastScreenshotResult)

            viewModel.dispatch(InspectorAction.DismissScreenshotResult)

            assertNull(viewModel.state.value.lastScreenshotResult)
        }

    @Test
    fun `a disabled screenshot result is still recorded, not swallowed`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.captureScreenshotResult = ScreenshotResult.Disabled
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.CaptureScreenshot)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ScreenshotResult.Disabled, viewModel.state.value.lastScreenshotResult)
        }

    @Test
    fun `mock rule mutations are a no-op when the mocks capability is disabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(mocks = false)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.UpsertMockRule(sampleMockRule()))
            viewModel.dispatch(InspectorAction.DeleteMockRule("rule-1"))
            viewModel.dispatch(InspectorAction.SetMockRuleEnabled("rule-1", false))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.upsertMockRuleCallCount)
            assertEquals(0, source.deleteMockRuleCallCount)
            assertEquals(0, source.setMockRuleEnabledCallCount)
            assertEquals(InspectorCommandResult.Disabled("mocks"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `mock rule mutations call through and refresh the snapshot when mocks is enabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(mocks = true)),
                )
            source.upsertMockRuleResult = InspectorCommandResult.Success(summary = "Mock rule saved")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.UpsertMockRule(sampleMockRule()))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.upsertMockRuleCallCount)
            assertEquals(source.upsertMockRuleResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `capture rule mutations are a no-op when the captureRules capability is disabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(captureRules = false)),
                )
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.UpsertCaptureRule(sampleCaptureRule()))
            viewModel.dispatch(InspectorAction.DeleteCaptureRule("capture-rule-1"))
            viewModel.dispatch(InspectorAction.SetCaptureRuleEnabled("capture-rule-1", false))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.upsertCaptureRuleCallCount)
            assertEquals(0, source.deleteCaptureRuleCallCount)
            assertEquals(0, source.setCaptureRuleEnabledCallCount)
            assertEquals(InspectorCommandResult.Disabled("captureRules"), viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `capture rule mutations call through and refresh the snapshot when captureRules is enabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source =
                RecordingInspectorDataSource(
                    InspectorSnapshot(available = true, capabilities = InspectorEditingUi(captureRules = true)),
                )
            source.upsertCaptureRuleResult = InspectorCommandResult.Success(summary = "Capture rule saved")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.UpsertCaptureRule(sampleCaptureRule()))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.upsertCaptureRuleCallCount)
            assertEquals(source.upsertCaptureRuleResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `dismissing the command result clears it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.exportHarResult = InspectorCommandResult.Success(summary = "Saved to har")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.ExportHar)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(source.exportHarResult, viewModel.state.value.lastCommandResult)

            viewModel.dispatch(InspectorAction.DismissCommandResult)

            assertNull(viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `previewing a file loads it and closing the preview clears it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.previewToReturn = InspectorFilePreviewUi.Text("hello", truncated = false)
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.PreviewFile("files", "a.txt"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(InspectorFilePreviewUi.Text("hello", truncated = false), viewModel.state.value.filePreview)

            viewModel.dispatch(InspectorAction.CloseFilePreview)

            assertNull(viewModel.state.value.filePreview)
        }

    @Test
    fun `the session-code pairing URL and QR-eligible fields are surfaced from the snapshot while running`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val browserWithSessionCode =
                sampleBrowser().copy(
                    sessionCodeUrl = "http://127.0.0.1:8080/#code=ABCD1234",
                    sessionCode = "ABCD1234",
                    sessionCodeExpiresAtEpochMs = 1_000L,
                    sessionCodeRemainingTtlMs = 500L,
                )
            val source =
                RecordingInspectorDataSource(InspectorSnapshot(available = true, browser = browserWithSessionCode))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            val browser = viewModel.state.value.browser
            assertEquals("http://127.0.0.1:8080/#code=ABCD1234", browser?.sessionCodeUrl)
            assertEquals("ABCD1234", browser?.sessionCode)
            assertEquals(1_000L, browser?.sessionCodeExpiresAtEpochMs)
            assertEquals(500L, browser?.sessionCodeRemainingTtlMs)
        }

    @Test
    fun `the session-code pairing URL disappears once the snapshot reports the server stopped`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val browserWithSessionCode =
                sampleBrowser().copy(sessionCodeUrl = "http://127.0.0.1:8080/#code=ABCD1234", sessionCode = "ABCD1234")
            val source =
                RecordingInspectorDataSource(InspectorSnapshot(available = true, browser = browserWithSessionCode))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "ABCD1234",
                viewModel.state.value.browser
                    ?.sessionCode,
            )

            source.snapshotToReturn = InspectorSnapshot(available = true, browser = sampleBrowser())
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(
                viewModel.state.value.browser
                    ?.sessionCodeUrl,
            )
            assertNull(
                viewModel.state.value.browser
                    ?.sessionCode,
            )
        }

    // ============================================================================================
    // Evidence tray -- InspectorState.flaggedTransactionIds/flaggedCrashIds are read from the
    // durable EvidenceStore through the data source, not held as local Compose state.
    // ============================================================================================

    @Test
    fun `initial load reflects a transaction flag that was written directly to the store by something else`() =
        runTest {
            // The data source's flaggedTransactionIds() stands in for the durable EvidenceStore's own
            // read here -- this view model never called flagTransaction itself, only polled the same
            // store the dashboard would have written to. This is the round trip that proves flags made
            // off-device actually reach this surface, not a local toggle formality.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val snapshot = InspectorSnapshot(available = true, transactions = listOf(sampleTransaction()))
            val source = RecordingInspectorDataSource(snapshot)
            source.flaggedTransactionIdsToReturn = setOf("tx-1")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("tx-1"), viewModel.state.value.flaggedTransactionIds)
        }

    @Test
    fun `a live refresh picks up a transaction flag added between polls`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                viewModel.state.value.flaggedTransactionIds
                    .isEmpty(),
            )

            source.flaggedTransactionIdsToReturn = setOf("tx-remote")
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("tx-remote"), viewModel.state.value.flaggedTransactionIds)
        }

    @Test
    fun `toggling an unflagged transaction flags it and refreshes the flagged set`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            // Simulates the store now holding the flag once flagTransaction() has run.
            source.flaggedTransactionIdsToReturn = setOf("tx-1")

            viewModel.dispatch(InspectorAction.ToggleTransactionFlag("tx-1"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.flagTransactionCallCount)
            assertEquals(0, source.unflagTransactionCallCount)
            assertEquals("tx-1", source.lastFlaggedTransactionId)
            assertEquals(setOf("tx-1"), viewModel.state.value.flaggedTransactionIds)
            assertEquals(source.flagTransactionResult, viewModel.state.value.lastCommandResult)
        }

    @Test
    fun `toggling an already-flagged transaction unflags it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.flaggedTransactionIdsToReturn = setOf("tx-1")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf("tx-1"), viewModel.state.value.flaggedTransactionIds)
            source.flaggedTransactionIdsToReturn = emptySet()

            viewModel.dispatch(InspectorAction.ToggleTransactionFlag("tx-1"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, source.flagTransactionCallCount)
            assertEquals(1, source.unflagTransactionCallCount)
            assertTrue(
                viewModel.state.value.flaggedTransactionIds
                    .isEmpty(),
            )
        }

    @Test
    fun `AlreadyFlagged and QuotaExceeded surface their messages instead of throwing or silently succeeding`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            source.flagTransactionResult = InspectorCommandResult.Invalid("Already flagged — see the evidence tray")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ToggleTransactionFlag("tx-1"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                InspectorCommandResult.Invalid("Already flagged — see the evidence tray"),
                viewModel.state.value.lastCommandResult,
            )

            source.flagTransactionResult =
                InspectorCommandResult.Invalid("Evidence tray is full (200 items) — clear some flags first")
            viewModel.dispatch(InspectorAction.ToggleTransactionFlag("tx-2"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                InspectorCommandResult.Invalid("Evidence tray is full (200 items) — clear some flags first"),
                viewModel.state.value.lastCommandResult,
            )
        }

    @Test
    fun `toggling a crash flag scopes flagCrash and its refresh to the selected session`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = sampleSession()
            val source =
                RecordingInspectorDataSource(InspectorSnapshot(available = true, sessions = listOf(session)))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.SelectSession(session.id))
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.dispatch(InspectorAction.ToggleCrashFlag("crash-1"))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, source.flagCrashCallCount)
            assertEquals("crash-1", source.lastFlaggedCrashId)
            assertEquals(session.id, source.lastFlaggedCrashSessionId)
        }

    @Test
    fun `a live refresh does not clobber a past session's flagged crash ids`() =
        runTest {
            // Regression-style counterpart to the identical logs/crashes guard: a live poll landing
            // while a past session is selected must not overwrite that session's flagged-crash set
            // with the live session's (here, the fake never returns anything for a live/null read).
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = sampleSession()
            val source =
                RecordingInspectorDataSource(InspectorSnapshot(available = true, sessions = listOf(session)))
            source.flaggedCrashIdsToReturn = setOf("past-crash")
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.SelectSession(session.id))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf("past-crash"), viewModel.state.value.flaggedCrashIds)

            source.flaggedCrashIdsToReturn = setOf("live-crash")
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("past-crash"), viewModel.state.value.flaggedCrashIds)
        }

    @Test
    fun `keep-alive prompt flag flows from snapshot to state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true, keepAlivePromptNeeded = true))

            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.state.value.keepAlivePromptNeeded)
        }

    // ============================================================================================
    // Capture-category gating (T4) -- InspectorState.captureCategories flows from the snapshot, and
    // a category that stops covering the currently-selected Observe tab must never strand the UI
    // there; see InspectorCaptureCategoryGatingTest for the pure visibleObserveTabs()/
    // visibleDestinations() gating rules themselves.
    // ============================================================================================

    @Test
    fun `initial load populates captureCategories from the snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val categories = setOf(CaptureCategory.SOCKET, CaptureCategory.MQTT)
            val source =
                RecordingInspectorDataSource(InspectorSnapshot(available = true, captureCategories = categories))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(categories, viewModel.state.value.captureCategories)
        }

    @Test
    fun `applying a snapshot whose categories exclude the currently selected tab moves observeTab to a visible one`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.SelectObserveTab(ObserveTab.PUSH))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(ObserveTab.PUSH, viewModel.state.value.observeTab)

            // PUSH drops out of the enabled set on the next poll -- only SOCKET stays on.
            source.snapshotToReturn =
                InspectorSnapshot(available = true, captureCategories = setOf(CaptureCategory.SOCKET))
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ObserveTab.SOCKETS, viewModel.state.value.observeTab)
        }

    @Test
    fun `a still-visible selected tab is left untouched when categories change`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.dispatch(InspectorAction.SelectObserveTab(ObserveTab.LOGS))
            dispatcher.scheduler.advanceUntilIdle()

            source.snapshotToReturn =
                InspectorSnapshot(
                    available = true,
                    captureCategories = setOf(CaptureCategory.LOGS, CaptureCategory.CRASHES),
                )
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ObserveTab.LOGS, viewModel.state.value.observeTab)
        }

    @Test
    fun `every category disabled leaves observeTab as-is instead of crashing on an empty visible list`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val source = RecordingInspectorDataSource(InspectorSnapshot(available = true))
            val viewModel = InspectorViewModel(dataSource = source, dispatcher = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            source.snapshotToReturn = InspectorSnapshot(available = true, captureCategories = emptySet())
            viewModel.dispatch(InspectorAction.Refresh)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ObserveTab.TRAFFIC, viewModel.state.value.observeTab)
            assertTrue(
                viewModel.state.value.captureCategories
                    .isEmpty(),
            )
        }
}

private fun sampleTransaction(id: String = "tx-1") =
    InspectorTransactionUi(
        id = id,
        method = "GET",
        host = "api.example.test",
        path = "/orders",
        statusCode = 200,
        durationMs = 42,
    )

private fun sampleComposerRequest() =
    InspectorComposerRequest(
        method = "POST",
        url = "https://api.example.test/orders",
        headers = mapOf("Content-Type" to "application/json"),
        body = "{}",
    )

private fun sampleSocket(id: String = "socket-1") =
    InspectorSocketUi(
        id = id,
        url = "wss://api.example.test/stream",
        state = "OPEN",
        sentCount = 1,
        receivedCount = 2,
        openedAtEpochMs = 100,
    )

private fun samplePush() =
    InspectorPushUi(
        provider = "fcm",
        messageId = "message-1",
        lifecycle = "DISPLAYED",
        simulated = false,
        receivedAtEpochMs = 200,
    )

private fun sampleLog() =
    InspectorLogUi(
        id = "log-1",
        kind = "ERROR",
        source = "Checkout",
        summary = "Checkout failed",
        timestampEpochMs = 300,
    )

private fun sampleCrash() =
    InspectorCrashUi(
        id = "crash-1",
        kind = "ANR",
        summary = "Main thread unresponsive",
        thread = "main",
        timestampEpochMs = 350,
        stackTrace = "\"main\"\n\tat com.example.Checkout.run(Checkout.kt:10)",
        breadcrumbs = listOf(InspectorBreadcrumbUi(340, "network", "request", 2, "POST /checkout")),
    )

private fun sampleFeatureFlag() =
    InspectorFeatureFlagUi(
        key = "dark_mode",
        value = "false",
        defaultValue = "false",
        allowedValues = listOf("true", "false"),
        type = "BOOLEAN",
        mutable = true,
        description = "Enables dark mode",
        isOverridden = false,
    )

private fun sampleStateProvider() =
    InspectorStateProviderUi(
        id = "preferences",
        entries = listOf(InspectorStateEntryUi(key = "theme", value = "dark", redacted = false)),
    )

// Wrapped is deliberate: see the identical conflict documented on
// UnavailableInspectorDataSource.upsertCaptureRule in InspectorDataSource.kt.
@Suppress("ktlint:standard:function-signature")
private fun sampleSession() =
    InspectorSessionUi(id = "session-1", startedAtEpochMs = 400, label = "DevConsole server started")

private fun sampleHealth() =
    InspectorHealthUi(
        state = "Started",
        initializationCount = 1,
        publishedEventCount = 42,
        droppedEventCount = 0,
    )

private fun sampleBrowser(principals: List<InspectorBrowserPrincipalUi> = emptyList()) =
    InspectorBrowserUi(binding = "LOOPBACK", endpoint = "127.0.0.1:8080", principals = principals)

private fun samplePrincipal() =
    InspectorBrowserPrincipalUi(
        id = "browser-1",
        label = "Chrome on Mac",
        sourceIp = "192.168.1.5",
        expiresAtEpochMs = 500,
    )

private fun sampleRetention() = InspectorRetentionUi(maxSessions = 10, maxAgeMs = 604_800_000L, maxBytes = 104_857_600L)

private fun sampleMockRule() =
    InspectorMockRuleUi(id = "rule-1", method = "GET", pathPattern = "/orders/*", actionLabel = "Return 500")

private fun sampleCaptureRule() = InspectorCaptureRuleUi(id = "capture-rule-1", host = "api.example.test")

private class RecordingInspectorDataSource(
    initialSnapshot: InspectorSnapshot = InspectorSnapshot(),
) : InspectorDataSource {
    var snapshotToReturn: InspectorSnapshot = initialSnapshot
    var executeResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "OK")
    var setMocksResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Mocks updated")
    var upsertMockRuleResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Mock rule saved")
    var deleteMockRuleResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Mock rule deleted")
    var setMockRuleEnabledResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Mock rule updated")
    var upsertCaptureRuleResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Capture rule saved")
    var deleteCaptureRuleResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Rule deleted")
    var setCaptureRuleEnabledResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Rule updated")
    var setFeatureFlagResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Flag updated")
    var setPreferenceResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Preference updated")
    var removePreferenceResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Preference removed")
    var listingToReturn: InspectorFileListingUi? = null
    var previewToReturn: InspectorFilePreviewUi = InspectorFilePreviewUi.Unavailable("n/a")
    var deleteFileResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "File deleted")
    var shareableFilePathResult: String? = null
    var tablesToReturn: InspectorDatabaseListingUi? = null
    var queryToReturn: InspectorQueryResultUi? = null
    var sqlResult: InspectorSqlResultUi = InspectorSqlResultUi.Failed("n/a")
    var exportHarResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Saved to har")
    var exportPostmanResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Saved to postman")
    var exportSessionZipResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Saved to zip")
    var revokePrincipalResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Browser revoked")
    var captureScreenshotResult: ScreenshotResult = ScreenshotResult.Failed("not configured")

    /** Stands in for the durable EvidenceStore's own read -- what "something else" (e.g. the dashboard) wrote. */
    var flaggedTransactionIdsToReturn: Set<String> = emptySet()
    var flagTransactionResult: InspectorCommandResult =
        InspectorCommandResult.Success(summary = "Flagged — added to evidence tray")
    var unflagTransactionResult: InspectorCommandResult =
        InspectorCommandResult.Success(summary = "Removed from evidence")
    var flaggedCrashIdsToReturn: Set<String> = emptySet()
    var flagCrashResult: InspectorCommandResult =
        InspectorCommandResult.Success(summary = "Flagged — added to evidence tray")
    var unflagCrashResult: InspectorCommandResult = InspectorCommandResult.Success(summary = "Removed from evidence")

    var executeCallCount = 0
        private set
    var setMocksEnabledCallCount = 0
        private set
    var upsertMockRuleCallCount = 0
        private set
    var deleteMockRuleCallCount = 0
        private set
    var setMockRuleEnabledCallCount = 0
        private set
    var upsertCaptureRuleCallCount = 0
        private set
    var deleteCaptureRuleCallCount = 0
        private set
    var setCaptureRuleEnabledCallCount = 0
        private set
    var setFeatureFlagCallCount = 0
        private set
    var setPreferenceCallCount = 0
        private set
    var removePreferenceCallCount = 0
        private set
    var listFilesCallCount = 0
        private set
    var deleteFileCallCount = 0
        private set
    var shareableFilePathCallCount = 0
        private set
    var lastListedPath: String? = null
        private set
    var executeSqlCallCount = 0
        private set
    var lastSql: String? = null
        private set
    var exportHarCallCount = 0
        private set
    var exportPostmanCallCount = 0
        private set
    var exportSessionZipCallCount = 0
        private set
    var revokePrincipalCallCount = 0
        private set
    var captureScreenshotCallCount = 0
        private set
    var lastRevokedPrincipalId: String? = null
        private set
    var lastExportHarSelection: Set<String>? = null
        private set
    var lastExportPostmanSelection: Set<String>? = null
        private set
    var lastExecuteRequest: InspectorComposerRequest? = null
        private set
    var lastMocksEnabledValue: Boolean? = null
        private set
    var lastFeatureFlagKey: String? = null
        private set
    var lastFeatureFlagValue: String? = null
        private set
    var lastPreferenceKey: String? = null
        private set
    var lastPreferenceValue: String? = null
        private set
    var flagTransactionCallCount = 0
        private set
    var unflagTransactionCallCount = 0
        private set
    var lastFlaggedTransactionId: String? = null
        private set
    var flagCrashCallCount = 0
        private set
    var unflagCrashCallCount = 0
        private set
    var lastFlaggedCrashId: String? = null
        private set
    var lastFlaggedCrashSessionId: String? = null
        private set
    var flaggedCrashIdsCallSessionIds = mutableListOf<String?>()
        private set

    override fun snapshot(): InspectorSnapshot = snapshotToReturn

    override suspend fun flaggedTransactionIds(): Set<String> = flaggedTransactionIdsToReturn

    override suspend fun flagTransaction(id: String): InspectorCommandResult {
        flagTransactionCallCount++
        lastFlaggedTransactionId = id
        return flagTransactionResult
    }

    override suspend fun unflagTransaction(id: String): InspectorCommandResult {
        unflagTransactionCallCount++
        lastFlaggedTransactionId = id
        return unflagTransactionResult
    }

    override suspend fun flaggedCrashIds(sessionId: String?): Set<String> {
        flaggedCrashIdsCallSessionIds += sessionId
        return flaggedCrashIdsToReturn
    }

    override suspend fun flagCrash(
        id: String,
        sessionId: String?,
    ): InspectorCommandResult {
        flagCrashCallCount++
        lastFlaggedCrashId = id
        lastFlaggedCrashSessionId = sessionId
        return flagCrashResult
    }

    override suspend fun unflagCrash(
        id: String,
        sessionId: String?,
    ): InspectorCommandResult {
        unflagCrashCallCount++
        lastFlaggedCrashId = id
        lastFlaggedCrashSessionId = sessionId
        return unflagCrashResult
    }

    override fun execute(request: InspectorComposerRequest): InspectorCommandResult {
        executeCallCount++
        lastExecuteRequest = request
        return executeResult
    }

    override fun setMocksEnabled(enabled: Boolean): InspectorCommandResult {
        setMocksEnabledCallCount++
        lastMocksEnabledValue = enabled
        return setMocksResult
    }

    override fun upsertMockRule(rule: InspectorMockRuleUi): InspectorCommandResult {
        upsertMockRuleCallCount++
        return upsertMockRuleResult
    }

    override fun deleteMockRule(id: String): InspectorCommandResult {
        deleteMockRuleCallCount++
        return deleteMockRuleResult
    }

    override fun setMockRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult {
        setMockRuleEnabledCallCount++
        return setMockRuleEnabledResult
    }

    override fun upsertCaptureRule(rule: InspectorCaptureRuleUi): InspectorCommandResult {
        upsertCaptureRuleCallCount++
        return upsertCaptureRuleResult
    }

    override fun deleteCaptureRule(id: String): InspectorCommandResult {
        deleteCaptureRuleCallCount++
        return deleteCaptureRuleResult
    }

    override fun setCaptureRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult {
        setCaptureRuleEnabledCallCount++
        return setCaptureRuleEnabledResult
    }

    override fun setFeatureFlag(
        key: String,
        value: String,
    ): InspectorCommandResult {
        setFeatureFlagCallCount++
        lastFeatureFlagKey = key
        lastFeatureFlagValue = value
        return setFeatureFlagResult
    }

    override fun setPreference(
        file: String,
        key: String,
        value: String,
        type: String,
    ): InspectorCommandResult {
        setPreferenceCallCount++
        lastPreferenceKey = key
        lastPreferenceValue = value
        return setPreferenceResult
    }

    override fun removePreference(
        file: String,
        key: String,
    ): InspectorCommandResult {
        removePreferenceCallCount++
        lastPreferenceKey = key
        return removePreferenceResult
    }

    override fun listFiles(
        root: String,
        relativePath: String,
    ): InspectorFileListingUi? {
        listFilesCallCount++
        lastListedPath = relativePath
        return listingToReturn
    }

    override fun previewFile(
        root: String,
        relativePath: String,
    ): InspectorFilePreviewUi = previewToReturn

    override fun deleteFile(
        root: String,
        relativePath: String,
    ): InspectorCommandResult {
        deleteFileCallCount++
        return deleteFileResult
    }

    override fun shareableFilePath(
        root: String,
        relativePath: String,
    ): String? {
        shareableFilePathCallCount++
        return shareableFilePathResult
    }

    override fun listTables(database: String): InspectorDatabaseListingUi? = tablesToReturn

    override fun queryTable(
        database: String,
        table: String,
    ): InspectorQueryResultUi? = queryToReturn

    override fun executeSql(
        database: String,
        sql: String,
    ): InspectorSqlResultUi {
        executeSqlCallCount++
        lastSql = sql
        return sqlResult
    }

    override fun exportHar(transactionIds: Set<String>): InspectorCommandResult {
        exportHarCallCount++
        lastExportHarSelection = transactionIds
        return exportHarResult
    }

    override fun exportPostman(transactionIds: Set<String>): InspectorCommandResult {
        exportPostmanCallCount++
        lastExportPostmanSelection = transactionIds
        return exportPostmanResult
    }

    override fun exportSessionZip(): InspectorCommandResult {
        exportSessionZipCallCount++
        return exportSessionZipResult
    }

    override fun revokePrincipal(id: String): InspectorCommandResult {
        revokePrincipalCallCount++
        lastRevokedPrincipalId = id
        return revokePrincipalResult
    }

    override suspend fun captureScreenshot(): ScreenshotResult {
        captureScreenshotCallCount++
        return captureScreenshotResult
    }
}
