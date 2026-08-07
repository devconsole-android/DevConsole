/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole.api

/**
 * Opt-in ways the SDK itself opens the in-app inspector -- see [DevConsoleConfig.openTriggers].
 * Everything defaults to off, so hosts that never touch this see no behaviour change. Triggers only
 * open the inspector UI; they never start the embedded server.
 */
data class OpenTriggers(
    val shakeToOpen: Boolean = false,
    val shakeIntensity: ShakeIntensity = ShakeIntensity.MEDIUM,
    val floatingButton: Boolean = false,
) {
    class Builder {
        private var shakeToOpen = false
        private var shakeIntensity = ShakeIntensity.MEDIUM
        private var floatingButton = false

        fun shakeToOpen(value: Boolean) = apply { shakeToOpen = value }

        fun shakeIntensity(value: ShakeIntensity) = apply { shakeIntensity = value }

        fun floatingButton(value: Boolean) = apply { floatingButton = value }

        fun build(): OpenTriggers =
            OpenTriggers(
                shakeToOpen = shakeToOpen,
                shakeIntensity = shakeIntensity,
                floatingButton = floatingButton,
            )
    }

    companion object {
        @JvmStatic
        fun disabled(): OpenTriggers = OpenTriggers()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/**
 * How hard a shake must be before the inspector opens -- see [OpenTriggers.shakeIntensity].
 */
enum class ShakeIntensity {
    /** A gentle wobble is enough. */
    LIGHT,

    /** A normal, intentional shake. */
    MEDIUM,

    /** Only a deliberate hard shake fires. */
    FIRM,
}
