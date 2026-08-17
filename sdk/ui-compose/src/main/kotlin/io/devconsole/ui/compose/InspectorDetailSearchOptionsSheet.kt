/**
 * @author Shakib
 * @since 17/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun InspectorDetailSearchOptionsSheet(
    options: InspectorDetailSearchOptions,
    selectedSectionKeys: Set<String>,
    mode: InspectorSearchMode,
    onDismiss: () -> Unit,
    onApply: (Set<String>, InspectorSearchMode) -> Unit,
) {
    var draftSectionKeys by remember(selectedSectionKeys) { mutableStateOf(selectedSectionKeys) }
    var draftMode by remember(mode) { mutableStateOf(mode) }
    val colors = DevConsoleTheme.colors

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Search options", color = colors.ink, fontSize = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                InspectorPillButton(
                    label = "Apply",
                    onClick = { onApply(draftSectionKeys, draftMode) },
                    enabled = draftSectionKeys.isNotEmpty(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Search in", color = colors.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { draftSectionKeys = options.sections.map { it.key }.toSet() }) {
                        Text("Select all")
                    }
                    TextButton(onClick = { draftSectionKeys = emptySet() }) {
                        Text("Clear all")
                    }
                }
                options.sections.forEach { section ->
                    val checked = section.key in draftSectionKeys
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(
                                    role = Role.Checkbox,
                                    onClick = {
                                        draftSectionKeys =
                                            if (checked) draftSectionKeys - section.key else draftSectionKeys + section.key
                                    },
                                )
                                .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(section.label, color = colors.ink, fontSize = 14.sp)
                    }
                }
                Text("Match", color = colors.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                FilterChipRow(
                    chips =
                        InspectorSearchMode.values().map { candidate ->
                            InspectorFilterChip(candidate.name, candidate.label, selected = candidate == draftMode)
                        },
                    onChipClick = { chip ->
                        draftMode = InspectorSearchMode.values().first { it.name == chip.id }
                    },
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
            }
        }
    }
}
