/**
 * @author Shakib
 * @since 03/11/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NUMERIC_FLAG_TYPES = setOf("INT", "INTEGER", "LONG", "NUMBER")
private val DECIMAL_FLAG_TYPES = setOf("FLOAT", "DOUBLE", "DECIMAL")

/** True for the flag types a soft numeric keypad suits. */
internal fun flagValueUsesNumericKeyboard(type: String): Boolean =
    type.uppercase() in NUMERIC_FLAG_TYPES || type.uppercase() in DECIMAL_FLAG_TYPES

/** Decimal-point keypad for fractional types; the plain number pad otherwise. */
internal fun flagValueKeyboardType(type: String): KeyboardType =
    if (type.uppercase() in DECIMAL_FLAG_TYPES) KeyboardType.Decimal else KeyboardType.Number

/**
 * Validates a typed flag value against its declared type: booleans must be literal true/false and
 * numeric types must parse. Every other type (the free-form STRING flags) accepts any non-blank text.
 */
internal fun isValidFeatureFlagValue(
    type: String,
    value: String,
): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    return when {
        type.equals("BOOLEAN", ignoreCase = true) -> trimmed.lowercase() == "true" || trimmed.lowercase() == "false"
        flagValueUsesNumericKeyboard(type) -> trimmed.toDoubleOrNull() != null
        else -> true
    }
}

/**
 * Single-choice editor for a mutable flag whose [InspectorFeatureFlagUi.allowedValues] names the
 * only values the host accepts. Picking an option confirms immediately; there is nothing to type.
 */
@Composable
internal fun FeatureFlagChoiceDialog(
    flag: InspectorFeatureFlagUi,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(flag.key) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                flag.allowedValues.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(option) }
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == flag.value, onClick = { onConfirm(option) })
                        Text(option, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Free-text editor for a mutable flag with no declared option list (STRING and numeric types).
 * [InspectorFeatureFlagUi.defaultValue] is one tap away, and an invalid number keeps Set disabled
 * rather than sending a value the host would reject.
 */
@Composable
internal fun FeatureFlagTextDialog(
    flag: InspectorFeatureFlagUi,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    var value by rememberSaveable(flag.key) { mutableStateOf(flag.value) }
    val numeric = flagValueUsesNumericKeyboard(flag.type)
    val valid = isValidFeatureFlagValue(flag.type, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(flag.key) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions =
                        if (numeric) {
                            KeyboardOptions(keyboardType = flagValueKeyboardType(flag.type))
                        } else {
                            KeyboardOptions.Default
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid) {
                    Text(
                        "Enter a valid ${flag.type.lowercase()} value",
                        color = colors.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "Default: ${flag.defaultValue}",
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { onConfirm(flag.defaultValue) }) {
                    Text("Reset to default", color = colors.signal, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = valid) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.muted) }
        },
    )
}
