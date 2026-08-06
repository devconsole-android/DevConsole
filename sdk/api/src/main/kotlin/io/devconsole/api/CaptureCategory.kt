/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

/**
 * A capture surface a host can independently opt into or out of at [DevConsoleConfig] construction
 * time -- see [DevConsoleConfig.captureCategories]. Every gate downstream is fail-open: a config that
 * is absent, or a gate lambda that throws, always means "capture", never "drop".
 */
enum class CaptureCategory(
    val wireName: String,
) {
    /** HTTP transactions (network recorder + Traffic views). */
    NETWORK("network"),

    /** WebSocket connections/frames. */
    SOCKET("socket"),

    /** MQTT connections/messages. */
    MQTT("mqtt"),

    /** Push events + dashboard push simulation. */
    PUSH("push"),

    /** Host log capture + Timeline/Logs views. */
    LOGS("logs"),

    /** Uncaught exceptions + ANR watchdog. */
    CRASHES("crashes"),

    /** State providers + feature flags. */
    STATE("state"),

    /** Preferences + database + files. */
    INSPECTION("inspection"),

    /** Mock engine + capture rules. */
    MOCKS("mocks"),
    ;

    companion object {
        @JvmStatic
        fun all(): Set<CaptureCategory> = entries.toSet()

        @JvmStatic
        fun none(): Set<CaptureCategory> = emptySet()

        @JvmStatic
        fun of(vararg values: CaptureCategory): Set<CaptureCategory> = values.toSet()

        @JvmStatic
        fun fromWireName(value: String?): CaptureCategory? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }

        /** Declaration order, stable across releases -- what the wire/JSON always sees. */
        @JvmStatic
        fun wireNames(values: Set<CaptureCategory>): List<String> = entries.filter { it in values }.map { it.wireName }
    }
}
