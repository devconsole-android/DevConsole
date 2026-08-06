/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "UnusedPrivateMember")

package io.devconsole.ui.compose

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
 * actions on the MORE destination as well as file shares on this one, and only the currently
 * selected destination's composable is part of the composition, so a `LaunchedEffect` here would
 * never fire for a share requested while MORE is showing.
 */
@Composable
internal fun DataRoute(
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InspectorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var heroCollapsed by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { viewModel.dispatch(InspectorAction.Refresh) }
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
 * Stateless, previewable Data surface: hero, read-only prefs rows, and the
 * existing Database/Files drill-ins restyled as [TonalListRow]s. Feature-flag editing moved to
 * Control's compact toggle rows; per this screen's own note ("Read-only on device"), Data no longer
 * offers an inline value editor at all.
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
) {
    val colors = DevConsoleTheme.colors
    val hero = rememberDataHero(state)
    // imePadding() so the SQL console's OutlinedTextField (DatabaseSection -> SqlConsoleCard) isn't
    // rendered underneath the soft keyboard -- same precedent as InspectorDetailScaffold.kt -- a
    // no-op when the IME is hidden. Applied here, at the route's outer container, rather than deep
    // inside DatabaseSection, so the whole LazyColumn shrinks and its own bringIntoView behavior
    // scrolls the focused field above the keyboard instead of just the field's own bounds moving.
    Column(modifier = modifier.fillMaxSize().background(colors.ground).imePadding()) {
        InspectorTopArea(
            subLine = "Preferences · database · files",
            title = "Data",
            actions = listOf(themeToggleTopAction(onToggleTheme)),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item { DataHero(hero, heroCollapsed, onToggleHero, colors) }
            item { Spacer(Modifier.height(16.dp)) }
            dataPreferenceRows(state, colors)
            item {
                Column {
                    Box(modifier = Modifier.padding(top = 4.dp)) {
                        FilesSection(state = state, canEdit = state.capabilities.files, actions = fileActions)
                    }
                    Box(modifier = Modifier.padding(top = 16.dp)) {
                        DatabaseSection(
                            state = state,
                            canWrite = state.capabilities.database,
                            actions = databaseActions,
                        )
                    }
                    DataStateProviders(state, colors)
                    WarnNote(
                        modifier = Modifier.padding(top = 16.dp),
                        text =
                            "Read-only on device. Editing lives on the dashboard, where there is a " +
                                "keyboard and a wider undo.",
                    )
                }
            }
        }
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
    state.preferenceFiles.forEach { file ->
        item { GroupLabel(file.name) }
        items(file.entries, key = { "${file.name}/${it.key}" }) { entry -> PreferenceRow(entry, colors) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PreferenceRow(
    entry: InspectorPreferenceEntryUi,
    colors: DevConsoleColors,
) {
    val style = prefRowStyle(entry, colors)
    TonalListRow(
        leadText = prefTypeLabel(entry.type),
        leadColor = style.leadColor,
        leadContainerColor = style.leadBg,
        title = entry.key,
        subtitle = if (entry.redacted) "••••••••••••••••" else entry.value,
        trailValue = style.trailValue,
        trailValueColor = style.trailColor,
        containerColor = style.rowBg,
    )
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
    remember(state.preferenceFiles, state.databases, state.fileRoots) {
        val entries = state.preferenceFiles.flatMap { it.entries }
        val blindSpots = entries.filter { looksLikeTokenKey(it.key) && !it.redacted }
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
    val containerColor = if (info.blindSpot) colors.errorSoft else colors.surface2
    val accentColor = if (info.blindSpot) colors.error else colors.text3
    val valueColor = if (info.blindSpot) colors.error else colors.ink
    val barLabel = if (info.blindSpot) "key${if (info.value == "1") "" else "s"} exposed by the allowlist" else info.of
    if (collapsed) {
        HeroBar(
            value = info.value,
            label = barLabel,
            onExpand = onToggleCollapse,
            containerColor = containerColor,
            valueColor = valueColor,
            labelColor = accentColor,
        )
    } else {
        HeroCard(
            label = info.label,
            value = info.value,
            valueSuffix = info.of,
            subtitle = info.sub,
            containerColor = containerColor,
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
) {
    if (state.stateProviders.isEmpty()) return
    Column(modifier = Modifier.padding(top = 16.dp)) {
        GroupLabel("State providers")
        state.stateProviders.forEach { provider ->
            provider.entries.forEach { entry -> StateEntryTonalRow(provider.id, entry, colors) }
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
        leadText = "VAR",
        leadColor = colors.put,
        leadContainerColor = colors.putSoft,
        title = "$providerId.${entry.key}",
        subtitle = if (entry.redacted) "redacted" else entry.value,
        trailValue = if (entry.redacted) "hidden" else "read",
        trailValueColor = colors.muted,
    )
}

/** Hand-built fixture for [DataScreenPreview] -- A2: previews are the only visual check on this branch. */
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
