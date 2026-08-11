/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")

package io.devconsole.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the inspector, mapped 1:1 from the design spec's `:root` token block (dark)
 * and `:root[data-theme="light"]` block (light). Prefer these over [MaterialTheme.colorScheme]
 * inside inspector components: the M3 scheme built in [DevConsoleTheme] only carries the subset of
 * roles default Material widgets read (Button, Scaffold, NavigationBar, ...); every custom role
 * (surface2/3, text3, warn, put, the soft tints, the JSON syntax palette) lives here instead.
 */
@Immutable
internal data class DevConsoleColors(
    val ground: Color,
    val panel: Color,
    val surface2: Color,
    val surface3: Color,
    val codeBg: Color,
    val ink: Color,
    val muted: Color,
    val text3: Color,
    val line: Color,
    val borderStrong: Color,
    val signal: Color,
    val signalInk: Color,
    val warn: Color,
    val error: Color,
    /**
     * Text/icon color for content painted on top of a saturated [error] fill (e.g. the traffic
     * hero's "Show only failures" CTA once armed). The design hardcodes a literal `#fff` at that
     * one call site regardless of theme rather than deriving it from a role -- this is that value
     * promoted to a token so no screen composable needs a raw hex literal.
     */
    val errorInk: Color,
    val put: Color,
    val signalSoft: Color,
    val errorSoft: Color,
    val warnSoft: Color,
    val putSoft: Color,
    val jsonKey: Color,
    val jsonString: Color,
    val jsonNumber: Color,
    val jsonBoolean: Color,
    val jsonNull: Color,
    /**
     * Depth-cycled brace/punctuation ladder for the JSON tree viewer, 5 entries indexed by
     * `depth % jsonBraces.size` -- mirrors the web dashboard's `--json-brace-0..4` (dashboard.css),
     * which cycles the same 5 tones per nesting level rather than rendering every brace in one flat
     * `--muted` tone.
     */
    val jsonBraces: List<Color>,
)

private val DarkPrimary = Color(0xFF72A7FF)
private val DarkError = Color(0xFFFF7B72)
private val DarkWarning = Color(0xFFF2B66D)
private val DarkSecondary = Color(0xFF72C4CE)

internal val DevConsoleDarkColors =
    DevConsoleColors(
        ground = Color(0xFF111317),
        panel = Color(0xFF171A20),
        surface2 = Color(0xFF1D2129),
        surface3 = Color(0xFF242A34),
        codeBg = Color(0xFF0D0F13),
        ink = Color(0xFFECEEF2),
        muted = Color(0xFF969DA8),
        text3 = Color(0xFF858D99),
        line = Color(0xFF343A45),
        borderStrong = Color(0xFF4A5260),
        signal = DarkPrimary,
        signalInk = Color(0xFF0E1624),
        warn = DarkWarning,
        error = DarkError,
        errorInk = Color(0xFF0E1624),
        put = DarkSecondary,
        signalSoft = DarkPrimary.copy(alpha = 0.13f),
        errorSoft = DarkError.copy(alpha = 0.13f),
        warnSoft = DarkWarning.copy(alpha = 0.13f),
        putSoft = DarkSecondary.copy(alpha = 0.13f),
        jsonKey = Color(0xFF8CB8FF),
        jsonString = Color(0xFFA9D6B9),
        jsonNumber = DarkWarning,
        jsonBoolean = Color(0xFFC7A7FF),
        jsonNull = Color(0xFF969DA8),
        jsonBraces = listOf(DarkPrimary, DarkWarning, DarkSecondary, Color(0xFFECEEF2), Color(0xFFC7A7FF)),
    )

private val LightPrimary = Color(0xFF245DA8)
private val LightError = Color(0xFFB6392A)
private val LightWarn = Color(0xFF93630A)
private val LightPut = Color(0xFF1F7A6C)

internal val DevConsoleLightColors =
    DevConsoleColors(
        ground = Color(0xFFF7F8FA),
        panel = Color(0xFFFFFFFF),
        surface2 = Color(0xFFEEF1F5),
        surface3 = Color(0xFFE4E8EE),
        codeBg = Color(0xFFF7F8FA),
        ink = Color(0xFF1C1F24),
        muted = Color(0xFF68707B),
        text3 = Color(0xFF68707B),
        line = Color(0xFFD5D9E0),
        borderStrong = Color(0xFFAAB1BC),
        signal = LightPrimary,
        signalInk = Color(0xFFFFFFFF),
        warn = LightWarn,
        error = LightError,
        errorInk = Color(0xFFFFFFFF),
        put = LightPut,
        signalSoft = LightPrimary.copy(alpha = 0.10f),
        errorSoft = LightError.copy(alpha = 0.10f),
        warnSoft = LightWarn.copy(alpha = 0.10f),
        putSoft = LightPut.copy(alpha = 0.10f),
        jsonKey = Color(0xFF1F7A6C),
        jsonString = Color(0xFF4A5B1F),
        jsonNumber = LightWarn,
        jsonBoolean = LightPrimary,
        jsonNull = Color(0xFF68707B),
        jsonBraces =
            listOf(
                LightPrimary,
                LightWarn,
                LightPut,
                Color(0xFF1C1F24),
                Color(0xFF6A4C93),
            ),
    )

/** The one elevation actually used (`--md-sys-elevation-3`), as a Compose dp level. */
internal object DevConsoleElevation {
    val level3 = 6.dp
}

/**
 * Bespoke type sizes that don't fit an M3 [MaterialTheme.typography] slot cleanly. Component-local
 * sizes called out inline in the design spec (tab labels, chip labels, etc.) are declared where
 * they're used instead of centralized here.
 */
internal object DevConsoleType {
    val title =
        TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, lineHeight = 31.sp)
    val rowTitle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.5.sp)
    val rowSub = TextStyle(fontSize = 12.5.sp)
    val groupLabel = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)
    val heroValue =
        TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em, lineHeight = 40.sp)
}

internal val DevConsoleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

internal val DevConsoleTypography = Typography(
    headlineSmall = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, lineHeight = 31.sp),
    bodyLarge = TextStyle(fontSize = 14.5.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp),
    labelMedium = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
)

internal val LocalDevConsoleColors = staticCompositionLocalOf { DevConsoleDarkColors }

/** Reads [DevConsoleColors] the same way `MaterialTheme.colorScheme` reads the M3 scheme. */
internal object DevConsoleTheme {
    val colors: DevConsoleColors
        @Composable get() = LocalDevConsoleColors.current
}

/**
 * The dark-default, light-capable palette shared by the workspace shell and every destination
 * surface. [darkTheme] defaults to `true` (not `isSystemInDarkTheme()`) because the app only ever
 * switches theme via its explicit toggle action, never by following the OS.
 */
@Composable
internal fun DevConsoleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DevConsoleDarkColors else DevConsoleLightColors
    val colorScheme = if (darkTheme) darkDevConsoleColorScheme(colors) else lightDevConsoleColorScheme(colors)
    CompositionLocalProvider(LocalDevConsoleColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = DevConsoleShapes,
            typography = DevConsoleTypography,
            content = content,
        )
    }
}

/**
 * The M3 role mapping the design spec specifies (`--md-sys-color-*`): `surface-container-low`
 * through `-high` onto `panel`/`surface2`/`surface3`, `primary` onto `signal`, etc.
 * [darkDevConsoleColorScheme] and [lightDevConsoleColorScheme] apply the same mapping to each
 * palette; kept as two functions (rather than one parameterized by `darkTheme`) because
 * [darkColorScheme] and [lightColorScheme] are distinct M3 factory functions.
 */
private fun darkDevConsoleColorScheme(colors: DevConsoleColors) =
    darkColorScheme(
        primary = colors.signal,
        onPrimary = colors.signalInk,
        primaryContainer = colors.signalSoft,
        onPrimaryContainer = colors.ink,
        secondary = colors.put,
        onSecondary = colors.signalInk,
        secondaryContainer = colors.putSoft,
        onSecondaryContainer = colors.put,
        tertiary = colors.warn,
        onTertiary = colors.signalInk,
        tertiaryContainer = colors.warnSoft,
        onTertiaryContainer = colors.warn,
        background = colors.ground,
        onBackground = colors.ink,
        surface = colors.ground,
        onSurface = colors.ink,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.ground,
        surfaceContainerLow = colors.panel,
        surfaceContainer = colors.surface2,
        surfaceContainerHigh = colors.surface3,
        surfaceContainerHighest = colors.surface3,
        surfaceTint = Color.Transparent,
        error = colors.error,
        onError = colors.signalInk,
        errorContainer = colors.errorSoft,
        onErrorContainer = colors.error,
        outline = colors.borderStrong,
        outlineVariant = colors.line,
    )

private fun lightDevConsoleColorScheme(colors: DevConsoleColors) =
    lightColorScheme(
        primary = colors.signal,
        onPrimary = colors.signalInk,
        primaryContainer = colors.signalSoft,
        onPrimaryContainer = colors.ink,
        secondary = colors.put,
        onSecondary = colors.signalInk,
        secondaryContainer = colors.putSoft,
        onSecondaryContainer = colors.put,
        tertiary = colors.warn,
        onTertiary = colors.signalInk,
        tertiaryContainer = colors.warnSoft,
        onTertiaryContainer = colors.warn,
        background = colors.ground,
        onBackground = colors.ink,
        surface = colors.ground,
        onSurface = colors.ink,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.ground,
        surfaceContainerLow = colors.panel,
        surfaceContainer = colors.surface2,
        surfaceContainerHigh = colors.surface3,
        surfaceContainerHighest = colors.surface3,
        surfaceTint = Color.Transparent,
        error = colors.error,
        onError = colors.signalInk,
        errorContainer = colors.errorSoft,
        onErrorContainer = colors.error,
        outline = colors.borderStrong,
        outlineVariant = colors.line,
    )
