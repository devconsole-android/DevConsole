/**
 * @author Shakib
 * @since 25/07/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devconsole.api.CaptureCategory

/** Grouped callbacks for the Room/SQLite database browser and its SQL console. */
data class DatabaseBrowserActions(
    val onOpenDatabase: (String) -> Unit,
    val onOpenTable: (String, String) -> Unit,
    val onExecuteSql: (String, String) -> Unit,
)

@Composable
internal fun DatabaseSection(
    state: InspectorState,
    canWrite: Boolean,
    actions: DatabaseBrowserActions,
) {
    // Defensive: databases belong to the INSPECTION category -- see FilesSection's identical guard.
    if (!state.captures(CaptureCategory.INSPECTION)) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.databases.isEmpty() -> InspectorEmptyState(EmptySpot.Database, "No app databases found.")
            else -> {
                DatabaseChips(
                    databases = state.databases,
                    selected = state.databaseListing?.name,
                    onOpenDatabase = actions.onOpenDatabase,
                )
                state.databaseListing?.let { listing ->
                    DatabaseTablesCard(listing = listing, onOpenTable = actions.onOpenTable)
                }
                state.queryResult?.let { result ->
                    GroupedPanel {
                        Column(Modifier.padding(16.dp)) {
                            Text("Table rows", color = DevConsoleTheme.colors.ink)
                            QueryResultTable(result)
                        }
                    }
                }
                SqlConsoleCard(
                    selectedDatabase = state.databaseListing?.name,
                    canWrite = canWrite,
                    sqlResult = state.sqlResult,
                    onExecuteSql = actions.onExecuteSql,
                )
            }
        }
    }
}

@Composable
private fun DatabaseChips(
    databases: List<String>,
    selected: String?,
    onOpenDatabase: (String) -> Unit,
) {
    val colors = DevConsoleTheme.colors
    GroupedPanel {
        databases.forEachIndexed { index, database ->
            if (index > 0) GroupedPanelDivider()
            val isSelected = database == selected
            Text(
                text = database,
                color = colors.ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .fillMaxWidth()
                        .clickable { onOpenDatabase(database) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .semantics { contentDescription = "Open database $database" },
            )
        }
    }
}

/** Shows a `name · size · table count` badge, with real bytes from `File.length()`. */
@Composable
private fun DatabaseSizeSubtitle(listing: InspectorDatabaseListingUi) {
    if (listing.sizeBytes <= 0) return
    val tableCount = listing.tables.size
    Text(
        text = "${formatByteSize(listing.sizeBytes)} · $tableCount ${if (tableCount == 1) "table" else "tables"}",
        color = DevConsoleTheme.colors.muted,
        fontFamily = FontFamily.Monospace,
        style = TerminalType.labelSmall,
    )
}

@Composable
private fun DatabaseTablesCard(
    listing: InspectorDatabaseListingUi,
    onOpenTable: (String, String) -> Unit,
) {
    GroupedPanel {
        Column(Modifier.padding(16.dp)) {
            Text(listing.name, color = DevConsoleTheme.colors.ink, fontFamily = FontFamily.Monospace)
            DatabaseSizeSubtitle(listing)
        }
        if (listing.tables.isEmpty()) {
            Text(
                text = "No tables found.",
                color = DevConsoleTheme.colors.muted,
                style = TerminalType.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            listing.tables.forEach { table ->
                GroupedPanelDivider()
                DatabaseTableRow(listing.name, table, onOpenTable)
            }
        }
    }
}

/** Uses [TonalListRow] to match the drill-in style used elsewhere in the Database/Files sections. */
@Composable
private fun DatabaseTableRow(
    database: String,
    table: InspectorDatabaseTableUi,
    onOpenTable: (String, String) -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        TonalListRow(
            leadText = "",
            leadColor = colors.put,
            leadContainerColor = colors.putSoft,
            title = table.name,
            subtitle = "",
            trailValue = "${table.rowCount} ${if (table.rowCount == 1L) "row" else "rows"}",
            trailValueColor = colors.muted,
            onClick = { onOpenTable(database, table.name) },
        )
    }
}

@Composable
private fun QueryResultTable(result: InspectorQueryResultUi) {
    val colors = DevConsoleTheme.colors
    Column(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row {
            result.columns.forEach { column ->
                Text(
                    column,
                    color = colors.ink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = TerminalType.bodySmall,
                    modifier = Modifier.width(160.dp).padding(8.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
        result.rows.forEach { row ->
            GroupedPanelDivider()
            Row {
                row.forEach { value ->
                    Text(
                        value,
                        color = colors.muted,
                        fontFamily = FontFamily.Monospace,
                        style = TerminalType.bodySmall,
                        modifier = Modifier.width(160.dp).padding(8.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        if (result.truncated) {
            Text(
                text = "… truncated",
                color = colors.muted,
                style = TerminalType.labelSmall,
            )
        }
    }
}

@Composable
private fun SqlConsoleCard(
    selectedDatabase: String?,
    canWrite: Boolean,
    sqlResult: InspectorSqlResultUi?,
    onExecuteSql: (String, String) -> Unit,
) {
    val colors = DevConsoleTheme.colors
    GroupedPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SQL console", color = colors.ink)
            var draft by remember { mutableStateOf("") }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TerminalType.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(12.dp),
                colors =
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.signal,
                        unfocusedBorderColor = colors.line,
                        focusedContainerColor = colors.panel,
                        unfocusedContainerColor = colors.panel,
                    ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "SQL statement input" },
            )
            if (!canWrite) {
                CapabilityDisabledNotice(
                    text = "Read-only — enable the database capability to run write statements.",
                )
            }
            val canRun = selectedDatabase != null && draft.isNotBlank()
            if (canRun) {
                InspectorPillButton(
                    label = "RUN",
                    onClick = { onExecuteSql(requireNotNull(selectedDatabase), draft) },
                    containerColor = colors.signal,
                    contentColor = colors.signalInk,
                    modifier = Modifier.semantics { contentDescription = "Run SQL statement" },
                )
            }
            sqlResult?.let { SqlResultView(it) }
        }
    }
}

@Composable
private fun SqlResultView(result: InspectorSqlResultUi) {
    val colors = DevConsoleTheme.colors
    when (result) {
        is InspectorSqlResultUi.Rows -> QueryResultTable(result.result)
        is InspectorSqlResultUi.Wrote ->
            Text(
                text = "${result.affectedRows} row(s) affected",
                color = colors.ink,
                style = TerminalType.bodySmall,
            )
        InspectorSqlResultUi.WriteBlocked ->
            Text(
                text = "Write statements are disabled by capability policy.",
                color = colors.muted,
                style = TerminalType.bodySmall,
            )
        is InspectorSqlResultUi.Failed ->
            Text(
                text = result.message,
                color = colors.muted,
                style = TerminalType.bodySmall,
            )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090D0B)
@Composable
private fun DatabaseSectionPreview() {
    DevConsoleTheme {
        DatabaseSection(
            state =
                InspectorState(
                    available = true,
                    capabilities = InspectorEditingUi(database = true),
                    databases = listOf("demo.db", "cache.db"),
                    databaseListing =
                        InspectorDatabaseListingUi(
                            name = "demo.db",
                            tables =
                                listOf(
                                    InspectorDatabaseTableUi("users", 42),
                                    InspectorDatabaseTableUi("orders", 7),
                                ),
                            sizeBytes = 4_300_000,
                        ),
                    queryResult =
                        InspectorQueryResultUi(
                            columns = listOf("id", "email"),
                            rows = listOf(listOf("1", "a@example.test")),
                            truncated = false,
                        ),
                ),
            canWrite = true,
            actions = DatabaseBrowserActions({}, { _, _ -> }, { _, _ -> }),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090D0B)
@Composable
private fun DatabaseSectionReadOnlyPreview() {
    DevConsoleTheme {
        DatabaseSection(
            state =
                InspectorState(
                    available = true,
                    capabilities = InspectorEditingUi(database = false),
                    databases = listOf("demo.db"),
                    databaseListing = InspectorDatabaseListingUi(name = "demo.db", tables = emptyList()),
                    sqlResult = InspectorSqlResultUi.WriteBlocked,
                ),
            canWrite = false,
            actions = DatabaseBrowserActions({}, { _, _ -> }, { _, _ -> }),
        )
    }
}
