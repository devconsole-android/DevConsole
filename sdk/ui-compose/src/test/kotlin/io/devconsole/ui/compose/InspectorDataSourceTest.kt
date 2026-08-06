package io.devconsole.ui.compose

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorDataSourceTest {
    @After
    fun resetBridge() {
        DevConsoleInspectorBridge.reset()
    }

    @Test
    fun `bridge is safely unavailable before full runtime installation`() {
        val snapshot = DevConsoleInspectorBridge.source().snapshot()

        assertFalse(snapshot.available)
        assertTrue(snapshot.transactions.isEmpty())
        assertFalse(snapshot.capabilities.requestExecution)
        assertFalse(snapshot.capabilities.mocks)
    }

    @Test
    fun `bridge exposes the installed full runtime source`() {
        val expected =
            InspectorSnapshot(
                available = true,
                transactions =
                    listOf(
                        InspectorTransactionUi(
                            id = "tx-1",
                            method = "GET",
                            host = "api.example.test",
                            path = "/orders",
                            statusCode = 200,
                            durationMs = 42,
                        ),
                    ),
                capabilities =
                    InspectorEditingUi(
                        requestExecution = true,
                        mocks = false,
                    ),
            )
        DevConsoleInspectorBridge.install(FakeInspectorDataSource(expected))

        assertEquals(expected, DevConsoleInspectorBridge.source().snapshot())
    }
}

private class FakeInspectorDataSource(
    private val value: InspectorSnapshot,
) : InspectorDataSource {
    override fun snapshot(): InspectorSnapshot = value

    override fun execute(request: InspectorComposerRequest): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setMocksEnabled(enabled: Boolean): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun upsertMockRule(rule: InspectorMockRuleUi): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun deleteMockRule(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setMockRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    // Wrapped is deliberate: see the identical conflict documented on
    // UnavailableInspectorDataSource.upsertCaptureRule in InspectorDataSource.kt.
    @Suppress("ktlint:standard:function-signature")
    override fun upsertCaptureRule(rule: InspectorCaptureRuleUi): InspectorCommandResult =
        InspectorCommandResult.Unavailable

    override fun deleteCaptureRule(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setCaptureRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setFeatureFlag(
        key: String,
        value: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setPreference(
        file: String,
        key: String,
        value: String,
        type: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun removePreference(
        file: String,
        key: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun listFiles(
        root: String,
        relativePath: String,
    ): InspectorFileListingUi? = null

    override fun previewFile(
        root: String,
        relativePath: String,
    ): InspectorFilePreviewUi = InspectorFilePreviewUi.Unavailable("n/a")

    override fun deleteFile(
        root: String,
        relativePath: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun listTables(database: String): InspectorDatabaseListingUi? = null

    override fun queryTable(
        database: String,
        table: String,
    ): InspectorQueryResultUi? = null

    override fun executeSql(
        database: String,
        sql: String,
    ): InspectorSqlResultUi = InspectorSqlResultUi.Failed("n/a")

    override fun exportHar(transactionIds: Set<String>): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun exportPostman(transactionIds: Set<String>): InspectorCommandResult = InspectorCommandResult.Unavailable
}
