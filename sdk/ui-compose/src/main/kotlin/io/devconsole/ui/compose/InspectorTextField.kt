/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A chrome-free single-line text field: no label, no indicator, just [value] rendered at
 * [textColor] with [placeholder] shown at [placeholderColor] while empty. Backs both
 * [InspectorSearchBar] and the detail screen's find field -- neither wants Material's default
 * `TextField` padding/underline, both want a plain pill look.
 */
@Suppress("LongParameterList") // Value/placeholder/colors are all independently supplied by each call site.
@Composable
internal fun InspectorPlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = TextStyle(color = textColor, fontSize = fontSize),
        singleLine = true,
        cursorBrush = SolidColor(textColor),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = placeholderColor,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            innerTextField()
        },
    )
}

/**
 * Same chrome-free contract as [InspectorPlainTextField] but multi-line, monospace by default --
 * backs the mock-rule create/edit sheet's response headers and body fields, neither of which fits
 * on one line.
 */
@Suppress("LongParameterList") // Value/placeholder/colors are all independently supplied by each call site.
@Composable
internal fun InspectorMultilineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = TextStyle(color = textColor, fontSize = fontSize, fontFamily = fontFamily),
        singleLine = false,
        cursorBrush = SolidColor(textColor),
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(placeholder, color = placeholderColor, fontSize = fontSize, fontFamily = fontFamily)
            }
            innerTextField()
        },
    )
}

/**
 * [TextFieldValue] flavour of [InspectorMultilineTextField], for the one caller that has to *move*
 * the selection rather than only read the text: the mock body's find arrows select the match they
 * step to. [onTextLayout] hands that caller the laid-out text so it can turn a match offset into a
 * rectangle worth scrolling to.
 */
@Suppress("LongParameterList") // Same contract as the String flavour above.
@Composable
internal fun InspectorMultilineTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = TextStyle(color = textColor, fontSize = fontSize, fontFamily = fontFamily),
        singleLine = false,
        cursorBrush = SolidColor(textColor),
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
        decorationBox = { innerTextField ->
            if (value.text.isEmpty()) {
                Text(placeholder, color = placeholderColor, fontSize = fontSize, fontFamily = fontFamily)
            }
            innerTextField()
        },
    )
}
