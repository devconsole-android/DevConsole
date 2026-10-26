/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "UnusedPrivateMember")

package io.devconsole.ui.compose

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.devconsole.api.CaptureCategory
import java.io.File
import java.util.Locale

/**
 * Stateful entry point wired into the DATA destination.
 *
 * The [InspectorState.pendingShareFilePath] side effect (launching the system Share sheet) is
 * handled once, at [DevConsoleWorkspace]'s top level, not here -- that path is set by export
 * actions on the MORE destination as well as file shares on this one. One workspace consumer
 * handles shares even while several destination routes remain composed.
 */
@Composable
internal fun DataRoute(
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InspectorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var heroCollapsed by rememberSaveable { mutableStateOf(true) }
    LifecycleStartEffect(Unit) {
        viewModel.dispatch(InspectorAction.Refresh)
        onStopOrDispose { }
    }
    DataScreen(
        state = state,
        heroCollapsed = heroCollapsed,
        onToggleHero = { heroCollapsed = !heroCollapsed },
        onToggleTheme = onToggleTheme,
        fileActions =
            FileBrowserActions(
                onOpenPath = { root, path -> viewModel.dispatch(InspectorAction.OpenFilePath(root, path)) },
                onPreview = { root, path -> viewModel.dispatch(InspectorAction.PreviewFile(root, path)) },
                onClosePreview = { viewModel.dispatch(InspectorAction.CloseFilePreview) },
                onDelete = { root, path -> viewModel.dispatch(InspectorAction.DeleteFile(root, path)) },
                onShare = { root, path -> viewModel.dispatch(InspectorAction.ShareFile(root, path)) },
            ),
        databaseActions =
            DatabaseBrowserActions(
                onOpenDatabase = { database -> viewModel.dispatch(InspectorAction.OpenDatabase(database)) },
                onOpenTable = { database, table -> viewModel.dispatch(InspectorAction.OpenTable(database, table)) },
                onExecuteSql = { database, sql -> viewModel.dispatch(InspectorAction.ExecuteSql(database, sql)) },
            ),
        modifier = modifier,
    )
}

/**
 * Builds a `content://` URI for [path] via the SDK's dedicated [FileProvider] authority and hands
 * it to the system Share sheet. [path] must be a regular file under one of [FileProvider]'s
 * declared roots (`devconsole_file_provider_paths.xml`, which mirrors `AndroidFileInspector`'s
 * sandbox roots) or [FileProvider.getUriForFile] throws; that failure -- and any failure to resolve
 * an activity that can handle `ACTION_SEND` -- is swallowed rather than crashing the host app, since
 * this is a debug-only convenience action.
 */
internal fun shareFileFromPath(
    context: Context,
    path: String,
) {
    val file = File(path)
    if (!file.isFile) return
    runCatching {
        val authority = "${context.packageName}.devconsole.files"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(sendIntent, "Share ${file.name}"))
    }
}

/**
 * Stateless, previewable Data surface: landing list of sources (Preferences, Database, Files,
 * App state) and real drill-in sub-screens for each source.
 */
@Composable
@Suppress("LongParameterList") // One callback group per real interaction this screen dispatches.
internal fun DataScreen(
    state: InspectorState,
    heroCollapsed: Boolean,
    onToggleHero: () -> Unit,
    onToggleTheme: () -> Unit,
    fileActions: FileBrowserActions,
    databaseActions: DatabaseBrowserActions,
    modifier: Modifier = Modifier,
    initialSource: String? = null,
) {
    val colors = DevConsoleTheme.colors
    val hero = rememberDataHero(state)
    var activeSource by rememberSaveable { mutableStateOf(initialSource) }

    val active = availableDataSource(activeSource, state)

    BackHandler(enabled = active != null) {
        activeSource = null
    }

    val fadeSpec = feedbackSpec<Float>()
    // imePadding() keeps the SQL console above the soft keyboard; a no-op while the IME is hidden.
    Column(modifier = modifier.fillMaxSize().background(colors.ground).imePadding()) {
        AnimatedContent(
            targetState = active,
            transitionSpec = { fadeIn(animationSpec = fadeSpec) togetherWith fadeOut(animationSpec = fadeSpec) },
            label = "dataSourceScreen",
            modifier = Modifier.fillMaxSize(),
        ) { sourceKey ->
            when (sourceKey) {
                null ->
                    DataSourceList(state, hero, heroCollapsed, onToggleHero, onToggleTheme, colors) {
                        activeSource = it
                    }
                "preferences" -> {
                    DataSourceSubScreen(title = "Preferences", onBack = { activeSource = null }) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                        ) {
                            dataPreferenceRows(state, colors)
                        }
                    }
                }
                "database" -> {
                    DataSourceSubScreen(title = "Database", onBack = { activeSource = null }) {
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 4.dp, bottom = 16.dp),
                        ) {
                            DatabaseSection(
                                state = state,
                                canWrite = state.capabilities.database,
                                actions = databaseActions,
                            )
                        }
                    }
                }
                "files" -> {
                    DataSourceSubScreen(title = "Files", onBack = { activeSource = null }) {
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 4.dp, bottom = 16.dp),
                        ) {
                            FilesSection(
                                state = state,
                                canEdit = state.capabilities.files,
                                actions = fileActions,
                            )
                        }
                    }
                }
                "state" -> {
                    DataSourceSubScreen(title = "App state", onBack = { activeSource = null }) {
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 4.dp, bottom = 16.dp),
                        ) {
                            DataStateProviders(state, colors)
                        }
                    }
                }
            }
        }
    }
}

/** The opened source, or null once it is no longer offered (capture turned off, providers gone). */
private fun availableDataSource(
    source: String?,
    state: InspectorState,
): String? =
    when (source) {
        "preferences", "database", "files" -> source.takeIf { state.captures(CaptureCategory.INSPECTION) }
        "state" -> source.takeIf { state.stateProviders.isNotEmpty() }
        else -> null
    }

@Composable
private fun DataSourceList(
    state: InspectorState,
    hero: DataHeroInfo,
    heroCollapsed: Boolean,
    onToggleHero: () -> Unit,
    onToggleTheme: () -> Unit,
    colors: DevConsoleColors,
    onOpenSource: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        InspectorTopArea(
            subLine = "",
            title = "Data",
            actions = listOf(themeToggleTopAction(onToggleTheme)),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (hero.blindSpot) {
                item {
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        DataHero(hero, heroCollapsed, onToggleHero, colors)
                    }
                }
            }
            item {
                GroupedPanel {
                    if (state.captures(CaptureCategory.INSPECTION)) {
                        DataSourceRow(
                            "Preferences",
                            "${state.preferenceFiles.size} files",
                            R.drawable.devconsole_ic_preferences,
                        ) {
                            onOpenSource("preferences")
                        }
                        GroupedPanelDivider()
                        DataSourceRow(
                            "Database",
                            state.databaseListing?.let { "${it.name} · ${it.tables.size} tables" }
                                ?: "${state.databases.size} databases",
                            R.drawable.devconsole_ic_database,
                        ) {
                            onOpenSource("database")
                        }
                        GroupedPanelDivider()
                        DataSourceRow(
                            "Files",
                            state.fileListing?.let { "${it.entries.size} items" }
                                ?: "${state.fileRoots.size} roots",
                            R.drawable.devconsole_ic_files,
                        ) {
                            onOpenSource("files")
                        }
                    }
                    if (state.stateProviders.isNotEmpty()) {
                        if (state.captures(CaptureCategory.INSPECTION)) GroupedPanelDivider()
                        DataSourceRow(
                            "App state",
                            state.stateProviders.size.let { "$it ${if (it == 1) "provider" else "providers"}" },
                            R.drawable.devconsole_ic_mocks,
                        ) {
                            onOpenSource("state")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataSourceRow(
    label: String,
    meta: String,
    icon: Int,
    onClick: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DevConsoleIcon(icon, null, Modifier, colors.muted, 22.dp)
        Text(label, color = colors.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(meta, color = colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.End)
        DevConsoleIcon(R.drawable.devconsole_ic_chevron_right, null, Modifier, colors.muted, 18.dp)
    }
}

/**
 * Real prefs entries as read-only rows -- no editor, per this screen's own "Read-only on device"
 * note. Defensive INSPECTION gate mirrors [FilesSection]/[DatabaseSection]'s: preferences belong to
 * the same category, and hiding the whole DATA destination is the primary gate, not this one.
 */
private fun LazyListScope.dataPreferenceRows(
    state: InspectorState,
    colors: DevConsoleColors,
) {
    if (!state.captures(CaptureCategory.INSPECTION)) return
    if (state.preferenceFiles.isEmpty()) {
        item { InspectorEmptyState(EmptySpot.Folder, "No SharedPreferences files found.") }
        return
    }
    state.preferenceFiles.forEach { file ->
        item { GroupLabel(file.name) }
        if (file.entries.isEmpty()) {
            item {
                GroupedPanel { Text("No preferences.", color = colors.muted, modifier = Modifier.padding(16.dp)) }
            }
        }
        itemsIndexed(file.entries, key = { _, entry -> "${file.name}/${entry.key}" }) { index, entry ->
            Column(Modifier.groupedPanelItem(GroupPosition.of(index, file.entries.size))) {
                PreferenceRow(entry, colors, state.redactionEnabled)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PreferenceRow(
    entry: InspectorPreferenceEntryUi,
    colors: DevConsoleColors,
    redactionEnabled: Boolean,
) {
    val style = prefRowStyle(entry, colors)
    // "verbatim" only means something once the host opted in to redaction; by default every value is shown.
    val showTokenNote = looksLikeTokenKey(entry.key) && (entry.redacted || redactionEnabled)
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.key, color = colors.ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            if (showTokenNote) Text(style.trailValue, color = style.trailColor, fontSize = 13.sp)
        }
        Text(
            if (entry.redacted) "••••••••••••••••" else entry.value,
            color = colors.muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class PrefRowStyle(
    val leadColor: Color,
    val leadBg: Color,
    val trailValue: String,
    val trailColor: Color,
    val rowBg: Color,
)

private fun prefRowStyle(
    entry: InspectorPreferenceEntryUi,
    colors: DevConsoleColors,
): PrefRowStyle =
    when {
        looksLikeTokenKey(entry.key) && !entry.redacted ->
            PrefRowStyle(colors.error, colors.errorSoft, "verbatim", colors.error, colors.errorSoft)
        looksLikeTokenKey(entry.key) ->
            PrefRowStyle(colors.warn, colors.warnSoft, "masked", colors.warn, colors.surface2)
        else -> PrefRowStyle(colors.put, colors.putSoft, "read", colors.muted, colors.surface2)
    }

private fun prefTypeLabel(type: String): String =
    when (type.uppercase(Locale.US)) {
        "STRING" -> "STR"
        "BOOLEAN" -> "BOOL"
        "INT", "INTEGER" -> "INT"
        "LONG" -> "LONG"
        "FLOAT" -> "FLT"
        "STRING_SET" -> "SET"
        else -> type.take(4).uppercase(Locale.US)
    }

/**
 * The real "redaction blind spot" signal: a token/secret-shaped preference key
 * ([InspectorPreferenceEntryUi.key]) whose value the SDK did *not* redact. Generalizes to any real
 * key that matches (e.g. `authToken`/`access_token`), rather than hard-coding those two literal
 * names.
 */
private val TOKEN_KEY_PATTERN = Regex("token|secret|password|credential", RegexOption.IGNORE_CASE)

private fun looksLikeTokenKey(key: String): Boolean = TOKEN_KEY_PATTERN.containsMatchIn(key)

/** Real, computed Data hero: an error-soft blind-spot warning when one exists, else a neutral summary. */
private data class DataHeroInfo(
    val blindSpot: Boolean,
    val value: String,
    val label: String,
    val of: String,
    val sub: String,
)

@Composable
private fun rememberDataHero(state: InspectorState): DataHeroInfo =
    remember(state.preferenceFiles, state.databases, state.fileRoots, state.redactionEnabled) {
        val entries = state.preferenceFiles.flatMap { it.entries }
        // With redaction off (the default) every value is shown on purpose, so nothing is a blind spot.
        val blindSpots =
            if (state.redactionEnabled) entries.filter { looksLikeTokenKey(it.key) && !it.redacted } else emptyList()
        val maskedTokenLike = entries.filter { looksLikeTokenKey(it.key) && it.redacted }
        if (blindSpots.isNotEmpty()) {
            dataBlindSpotHero(blindSpots, maskedTokenLike)
        } else {
            dataNeutralHero(entries.size, state)
        }
    }

private fun dataBlindSpotHero(
    blindSpots: List<InspectorPreferenceEntryUi>,
    maskedTokenLike: List<InspectorPreferenceEntryUi>,
): DataHeroInfo {
    val first = blindSpots.first()
    val sub =
        buildString {
            append("${first.key} is not on the deny list, so its value is transmitted verbatim.")
            maskedTokenLike.firstOrNull()?.let { append(" ${it.key} is masked.") }
        }
    val of = if (blindSpots.size == 1) "key exposed" else "keys exposed"
    return DataHeroInfo(true, blindSpots.size.toString(), "Redaction blind spot", of, sub)
}

private fun dataNeutralHero(
    prefCount: Int,
    state: InspectorState,
): DataHeroInfo {
    val sub =
        "$prefCount preference key${if (prefCount == 1) "" else "s"} across ${state.preferenceFiles.size} " +
            "file(s), ${state.databases.size} database(s), ${state.fileRoots.size} file root(s) tracked."
    return DataHeroInfo(false, prefCount.toString(), "Preferences & storage", "keys tracked", sub)
}

@Composable
private fun DataHero(
    info: DataHeroInfo,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    colors: DevConsoleColors,
) {
    val accentColor = if (info.blindSpot) colors.error else colors.text3
    val valueColor = if (info.blindSpot) colors.error else colors.ink
    val barLabel = if (info.blindSpot) "key${if (info.value == "1") "" else "s"} exposed by the allowlist" else info.of
    if (collapsed) {
        HeroBar(
            value = info.value,
            label = barLabel,
            onExpand = onToggleCollapse,
            valueColor = valueColor,
            labelColor = accentColor,
        )
    } else {
        HeroCard(
            label = info.label,
            value = info.value,
            valueSuffix = info.of,
            subtitle = info.sub,
            labelColor = accentColor,
            valueColor = valueColor,
            onCollapse = onToggleCollapse,
        )
    }
}

/**
 * State providers have no home in the design spec's Data view, but dropping their visibility
 * entirely would be a real functional regression for a host that registers one -- kept as a
 * compact, read-only group appended after the existing Database/Files drill-ins.
 */
@Composable
private fun DataStateProviders(
    state: InspectorState,
    colors: DevConsoleColors,
    modifier: Modifier = Modifier,
) {
    if (state.stateProviders.isEmpty()) return
    Column(modifier = modifier) {
        GroupLabel("State providers")
        GroupedPanel {
            val entries = state.stateProviders.flatMap { provider -> provider.entries.map { provider.id to it } }
            entries.forEachIndexed { index, (id, entry) ->
                if (index > 0) GroupedPanelDivider()
                StateEntryTonalRow(id, entry, colors)
            }
        }
    }
}

@Composable
private fun StateEntryTonalRow(
    providerId: String,
    entry: InspectorStateEntryUi,
    colors: DevConsoleColors,
) {
    TonalListRow(
        leadText = "",
        leadColor = colors.put,
        leadContainerColor = colors.putSoft,
        title = "$providerId.${entry.key}",
        subtitle = if (entry.redacted) "redacted" else "",
        trailValue = if (entry.redacted) "••••••••" else entry.value,
        trailValueColor = colors.muted,
    )
}

/** Hand-built fixture for [DataScreenPreview]. */
private fun dataScreenPreviewState() =
    InspectorState(
        available = true,
        preferenceFiles =
            listOf(
                InspectorPreferenceFileUi(
                    name = "app_prefs",
                    entries =
                        listOf(
                            InspectorPreferenceEntryUi("authToken", "eyJhbGciOi...", "STRING", redacted = false),
                            InspectorPreferenceEntryUi("theme", "dark", "STRING", redacted = false),
                        ),
                ),
            ),
        fileRoots = listOf("files", "cache"),
        fileListing =
            InspectorFileListingUi(
                root = "files",
                relativePath = "logs",
                entries =
                    listOf(
                        InspectorFileEntryUi(
                            name = "archive",
                            relativePath = "logs/archive",
                            isDirectory = true,
                            sizeBytes = 0,
                            lastModifiedEpochMs = 0,
                        ),
                        InspectorFileEntryUi(
                            name = "today.log",
                            relativePath = "logs/today.log",
                            isDirectory = false,
                            sizeBytes = 2048,
                            lastModifiedEpochMs = 0,
                        ),
                    ),
            ),
        databases = listOf("app.db"),
        stateProviders =
            listOf(
                InspectorStateProviderUi(
                    id = "cart",
                    entries = listOf(InspectorStateEntryUi("itemCount", "3", redacted = false)),
                ),
            ),
    )

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun DataScreenPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        DataScreen(
            state = dataScreenPreviewState(),
            heroCollapsed = false,
            onToggleHero = {},
            onToggleTheme = {},
            fileActions = FileBrowserActions({ _, _ -> }, { _, _ -> }, {}, { _, _ -> }),
            databaseActions = DatabaseBrowserActions({}, { _, _ -> }, { _, _ -> }),
        )
    }
}
