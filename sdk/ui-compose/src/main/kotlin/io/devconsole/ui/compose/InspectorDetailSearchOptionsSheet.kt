/**
 * @author Shakib
 * @since 17/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun InspectorDetailSearchOptionsSheet(
    options: InspectorDetailSearchOptions,
    selectedSectionKeys: Set<String>,
    mode: InspectorSearchMode,
    onDismiss: () -> Unit,
    onApply: (Set<String>, InspectorSearchMode) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DevConsoleTheme.colors.panel,
        // A half-height first view would cut the section list and hide the mode chips entirely.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        InspectorDetailSearchOptionsSheetContent(
            options = options,
            selectedSectionKeys = selectedSectionKeys,
            mode = mode,
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

@Composable
@Suppress("LongParameterList") // Mirrors the sheet's own signature, plus the conventional modifier.
private fun InspectorDetailSearchOptionsSheetContent(
    options: InspectorDetailSearchOptions,
    selectedSectionKeys: Set<String>,
    mode: InspectorSearchMode,
    onDismiss: () -> Unit,
    onApply: (Set<String>, InspectorSearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftSectionKeys by remember(selectedSectionKeys) { mutableStateOf(selectedSectionKeys) }
    var draftMode by remember(mode) { mutableStateOf(mode) }
    val colors = DevConsoleTheme.colors

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
    ) {
        SearchOptionsHeader(
            applyEnabled = draftSectionKeys.isNotEmpty(),
            onDismiss = onDismiss,
            onApply = { onApply(draftSectionKeys, draftMode) },
        )
        HorizontalDivider(color = colors.line)
        // No weight: the sheet sizes to its content, and this column only starts scrolling once the
        // sections outgrow the height the sheet is allowed (short lists get a short sheet).
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchSectionPicker(
                sections = options.sections,
                selectedKeys = draftSectionKeys,
                onSelectionChange = { draftSectionKeys = it },
            )
            SearchOptionsGroupLabel("Match on")
            FilterChipRow(
                chips =
                    InspectorSearchMode.entries.map { candidate ->
                        InspectorFilterChip(candidate.name, candidate.label, selected = candidate == draftMode)
                    },
                onChipClick = { chip -> draftMode = InspectorSearchMode.valueOf(chip.id) },
                contentPadding = PaddingValues(vertical = 8.dp),
            )
            Text(draftMode.hint, color = colors.text3, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchOptionsHeader(
    applyEnabled: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Search options",
            color = DevConsoleTheme.colors.ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).em,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Cancel") }
        InspectorPillButton(
            label = "Apply",
            onClick = onApply,
            enabled = applyEnabled,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SearchSectionPicker(
    sections: List<InspectorDetailSearchSection>,
    selectedKeys: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
) {
    SearchOptionsGroupLabel("Search in")
    Row(
        // Cancels TextButtonHorizontalPadding so the labels sit on the column's own 20dp margin.
        modifier = Modifier.fillMaxWidth().offset(x = (-12).dp),
    ) {
        TextButton(onClick = { onSelectionChange(sections.map { it.key }.toSet()) }) { Text("Select all") }
        TextButton(onClick = { onSelectionChange(emptySet()) }) { Text("Clear all") }
    }
    sections.forEach { section ->
        SearchSectionCheckboxRow(
            label = section.label,
            checked = section.key in selectedKeys,
            onCheckedChange = { isChecked ->
                onSelectionChange(if (isChecked) selectedKeys + section.key else selectedKeys - section.key)
            },
        )
    }
    if (selectedKeys.isEmpty()) {
        Text(
            "Pick at least one section before applying.",
            color = DevConsoleTheme.colors.warn,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The design system's Label voice: uppercase, tracked, tertiary -- the sheet's only wayfinding. */
@Composable
private fun SearchOptionsGroupLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        color = DevConsoleTheme.colors.text3,
        style = DevConsoleType.groupLabel,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/**
 * One searchable section. The whole row is the toggle: [Checkbox] is passed a null callback so the
 * row's `toggleable` owns both the click and the checked state TalkBack announces.
 */
@Composable
private fun SearchSectionCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, color = DevConsoleTheme.colors.ink, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Preview(name = "Large font", fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun InspectorDetailSearchOptionsSheetPreview() {
    SearchOptionsSheetPreviewScaffold(
        selectedSectionKeys = NetworkDetailSearchOptions.defaultSectionKeys,
    )
}

/** The state that disables Apply: no section selected, so the warning and the gated action show. */
@Preview(
    name = "Nothing selected",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    backgroundColor = 0xFF0B0E0D,
)
@Composable
private fun InspectorDetailSearchOptionsSheetEmptyPreview() {
    SearchOptionsSheetPreviewScaffold(selectedSectionKeys = emptySet())
}

@Composable
private fun SearchOptionsSheetPreviewScaffold(selectedSectionKeys: Set<String>) {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = DevConsoleTheme.colors.panel) {
            InspectorDetailSearchOptionsSheetContent(
                options = NetworkDetailSearchOptions,
                selectedSectionKeys = selectedSectionKeys,
                mode = NetworkDetailSearchOptions.defaultMode,
                onDismiss = {},
                onApply = { _, _ -> },
            )
        }
    }
}
