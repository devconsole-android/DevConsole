/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.graphics.Outline
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import io.devconsole.api.OpenTriggers
import io.devconsole.api.ShakeIntensity
import io.devconsole.full.R
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs [io.devconsole.api.DevConsoleConfig.openTriggers]: shake-to-open and the floating button.
 * Both triggers attach only while an Activity is resumed and detach on pause, and the controller
 * only ever holds the resumed Activity through a [WeakReference] -- the same discipline as
 * [ActivityTracker]. Triggers only open the inspector UI; nothing here can start the embedded
 * server.
 */
@Suppress("TooManyFunctions") // Half are the empty ActivityLifecycleCallbacks/SensorEventListener overrides.
internal class OpenTriggerController(
    private val application: Application,
    private val openInspector: (Activity) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Application.ActivityLifecycleCallbacks,
    SensorEventListener {
    private val registered = AtomicBoolean(false)

    @Volatile private var triggers = OpenTriggers()

    // Most recent last. A deque rather than a single reference because Android 10+ multi-resume
    // (split-screen, freeform) keeps several activities resumed at once: pausing the last-resumed
    // one must fall back to a survivor, not silently stop the sensor for a still-visible activity.
    private val resumedActivities = ArrayDeque<WeakReference<Activity>>()
    private var sensorListening = false
    private val shakeDetector = ShakeDetector(now)
    private var lastOpenAtMs = Long.MIN_VALUE / 2

    /** Last dragged position; kept here rather than on the button so it survives Activity changes. */
    private var buttonX: Float? = null
    private var buttonY: Float? = null

    /** Registration must happen exactly once per Application, regardless of how many times initialize runs. */
    fun registerOnce(application: Application) {
        if (registered.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(this)
        }
    }

    /** Idempotent; runs again on every initialize, including a host config superseding provisional auto-init. */
    fun reconfigure(triggers: OpenTriggers) {
        this.triggers = triggers
        shakeDetector.updateIntensity(triggers.shakeIntensity)
        applyToResumed()
    }

    override fun onActivityResumed(activity: Activity) {
        // The inspector itself never gets triggers: a trigger firing while it is resumed would
        // stack a second inspector instance.
        if (activity.javaClass.name == INSPECTOR_ACTIVITY_CLASS_NAME) return
        resumedActivities.removeAll { it.get() === activity || it.get() == null }
        resumedActivities.addLast(WeakReference(activity))
        applyToResumed()
    }

    override fun onActivityPaused(activity: Activity) {
        val wasCurrent = currentResumed() === activity
        resumedActivities.removeAll { it.get() === activity || it.get() == null }
        detachFloatingButton(activity)
        if (wasCurrent) applyToResumed()
    }

    private fun currentResumed(): Activity? {
        while (resumedActivities.isNotEmpty()) {
            val activity = resumedActivities.last().get()
            if (activity != null) return activity
            resumedActivities.removeLast()
        }
        return null
    }

    private fun applyToResumed() {
        val activity = currentResumed()
        if (activity == null) {
            stopShakeListening()
            return
        }
        if (triggers.shakeToOpen) startShakeListening() else stopShakeListening()
        if (triggers.floatingButton) attachFloatingButton(activity) else detachFloatingButton(activity)
    }

    /** Both triggers funnel through here; the debounce stops a double-tap (or shake + tap) racing two launches. */
    private fun openFromTrigger(activity: Activity) {
        val nowMs = now()
        if (nowMs - lastOpenAtMs < OPEN_DEBOUNCE_MS) return
        lastOpenAtMs = nowMs
        openInspector(activity)
    }

    // Guard-clause early returns (already listening, no sensor service, no accelerometer) are the
    // clearest form here -- see RoomAttachmentStore.kt for the same rationale.
    @Suppress("ReturnCount")
    private fun startShakeListening() {
        if (sensorListening) return
        val sensorManager = application.getSystemService(SensorManager::class.java) ?: return
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        shakeDetector.reset()
        sensorListening = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopShakeListening() {
        if (!sensorListening) return
        sensorListening = false
        application.getSystemService(SensorManager::class.java)?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.size < ACCELEROMETER_AXES) return
        val magnitudeG =
            sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]) /
                SensorManager.GRAVITY_EARTH
        if (shakeDetector.onSample(magnitudeG)) {
            currentResumed()?.let(::openFromTrigger)
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    // performClick() is invoked on tap in FloatingButtonDragHandler, satisfying what the lint
    // check actually wants; it cannot see through the drag/tap split.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachFloatingButton(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(BUTTON_TAG) != null) return
        val density = activity.resources.displayMetrics.density
        val button =
            ImageView(activity).apply {
                tag = BUTTON_TAG
                setImageResource(R.drawable.devconsole_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = BUTTON_CONTENT_DESCRIPTION
                elevation = BUTTON_ELEVATION_DP * density
                // Sits on top of the host's own UI, so it stays translucent at rest rather than
                // hiding whatever it happens to be parked over; touching it returns it to full
                // opacity, which also makes it obvious what you have hold of while dragging.
                alpha = BUTTON_IDLE_ALPHA
                // The mark carries its own rounded-square plate, so the button needs no background of
                // its own -- but an elevation shadow is cast from the view's outline, which defaults to
                // the full rectangle and would leak out past the artwork's transparent corners.
                outlineProvider = RoundedSquareOutline(BUTTON_CORNER_RADIUS_FRACTION)
                clipToOutline = true
                setOnClickListener { openFromTrigger(activity) }
                setOnTouchListener(FloatingButtonDragHandler(ViewConfiguration.get(activity).scaledTouchSlop))
                // The stored position is raw pixels from whatever window it was last dragged in; a
                // rotation or a smaller window could otherwise park it wholly off-screen, where it
                // is unrecoverable. Re-clamp on every layout so it always stays reachable.
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> clampIntoParent(view) }
            }
        val sizePx = (BUTTON_SIZE_DP * density).toInt()
        val params = FrameLayout.LayoutParams(sizePx, sizePx)
        val rememberedX = buttonX
        val rememberedY = buttonY
        if (rememberedX != null && rememberedY != null) {
            params.gravity = Gravity.TOP or Gravity.START
        } else {
            params.gravity = Gravity.BOTTOM or Gravity.END
            val margin = (BUTTON_DEFAULT_MARGIN_DP * density).toInt()
            params.setMargins(margin, margin, margin, margin)
        }
        decor.addView(button, params)
        if (rememberedX != null && rememberedY != null) {
            button.x = rememberedX
            button.y = rememberedY
        }
    }

    private fun detachFloatingButton(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(BUTTON_TAG)?.let(decor::removeView)
    }

    /** Movement past the touch slop becomes a drag; anything shorter falls through to the click. */
    private inner class FloatingButtonDragHandler(
        private val touchSlop: Int,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false

        override fun onTouch(
            view: View,
            event: MotionEvent,
        ): Boolean =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    dragging = false
                    view.alpha = BUTTON_ACTIVE_ALPHA
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) moveTo(view, startX + dx, startY + dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.alpha = BUTTON_IDLE_ALPHA
                    if (!dragging) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = BUTTON_IDLE_ALPHA
                    true
                }
                else -> false
            }

        private fun moveTo(
            view: View,
            x: Float,
            y: Float,
        ) {
            view.x = x
            view.y = y
            clampIntoParent(view)
            buttonX = view.x
            buttonY = view.y
        }
    }

    private fun clampIntoParent(view: View) {
        val parent = view.parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val maxX = (parent.width - view.width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - view.height).coerceAtLeast(0).toFloat()
        val clampedX = view.x.coerceIn(0f, maxX)
        val clampedY = view.y.coerceIn(0f, maxY)
        if (clampedX != view.x) view.x = clampedX
        if (clampedY != view.y) view.y = clampedY
        if (buttonX != null) {
            buttonX = view.x
            buttonY = view.y
        }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    internal companion object {
        const val INSPECTOR_ACTIVITY_CLASS_NAME = "io.devconsole.ui.compose.DevConsoleActivity"
        const val BUTTON_TAG = "io.devconsole.openTriggerFloatingButton"
        const val BUTTON_CONTENT_DESCRIPTION = "Open DevConsole"
        const val BUTTON_SIZE_DP = 48
        const val BUTTON_DEFAULT_MARGIN_DP = 16
        const val BUTTON_ELEVATION_DP = 8

        /** Matches the corner radius drawn into `devconsole_logo`, as a fraction of its width. */
        const val BUTTON_CORNER_RADIUS_FRACTION = 0.193f

        /**
         * Resting and touched opacity. The button floats over the host app's own UI for the whole
         * session, so at rest it stays legible without fully hiding what is beneath it; any touch
         * takes it to opaque, which doubles as the drag affordance.
         */
        const val BUTTON_IDLE_ALPHA = 0.65f
        const val BUTTON_ACTIVE_ALPHA = 1f
        const val OPEN_DEBOUNCE_MS = 1_000L
        private const val ACCELEROMETER_AXES = 3
    }
}

/**
 * Traces the floating button's elevation shadow (and its touch ripple, via `clipToOutline`) around
 * the rounded-square plate `devconsole_logo` draws, instead of the view's full rectangle -- the
 * artwork's corners are transparent, so the default outline would cast a squared-off shadow into
 * empty pixels.
 *
 * A true squircle cannot be expressed as an [Outline]; a plain rounded rect at the same radius is
 * close enough at 48dp, where the difference is well under a pixel.
 */
internal class RoundedSquareOutline(
    private val cornerRadiusFraction: Float,
) : ViewOutlineProvider() {
    override fun getOutline(
        view: View,
        outline: Outline,
    ) {
        outline.setRoundRect(0, 0, view.width, view.height, view.width * cornerRadiusFraction)
    }
}

/**
 * Accelerometer magnitudes (in g) in, discrete shake decisions out. Two over-threshold samples
 * within [PEAK_WINDOW_MS] make one shake -- a single spike (a bump, a drop onto a desk) never
 * fires -- and each fire is followed by a [COOLDOWN_MS] cooldown. The clock is injectable so the
 * math tests without sensors.
 */
internal class ShakeDetector(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    @Volatile private var thresholdG = ShakeIntensity.MEDIUM.thresholdG()
    private var firstPeakAtMs = NO_PEAK
    private var cooldownUntilMs = Long.MIN_VALUE

    fun updateIntensity(intensity: ShakeIntensity) {
        thresholdG = intensity.thresholdG()
    }

    /** Clears any half-counted shake; the cooldown deliberately survives so re-resuming cannot re-fire early. */
    fun reset() {
        firstPeakAtMs = NO_PEAK
    }

    /** True when this sample completes a shake; the caller then opens the inspector. */
    @Suppress("ReturnCount") // Guard-clause early returns (under threshold, in cooldown) are the clearest form here.
    fun onSample(magnitudeG: Float): Boolean {
        if (magnitudeG < thresholdG) return false
        val nowMs = now()
        if (nowMs < cooldownUntilMs) return false
        val firstPeak = firstPeakAtMs
        if (firstPeak != NO_PEAK && nowMs - firstPeak <= PEAK_WINDOW_MS) {
            firstPeakAtMs = NO_PEAK
            cooldownUntilMs = nowMs + COOLDOWN_MS
            return true
        }
        firstPeakAtMs = nowMs
        return false
    }

    internal companion object {
        const val PEAK_WINDOW_MS = 400L
        const val COOLDOWN_MS = 1_500L
        private const val NO_PEAK = Long.MIN_VALUE
    }
}

internal fun ShakeIntensity.thresholdG(): Float =
    when (this) {
        ShakeIntensity.LIGHT -> LIGHT_THRESHOLD_G
        ShakeIntensity.MEDIUM -> MEDIUM_THRESHOLD_G
        ShakeIntensity.FIRM -> FIRM_THRESHOLD_G
    }

private const val LIGHT_THRESHOLD_G = 1.9f
private const val MEDIUM_THRESHOLD_G = 2.5f
private const val FIRM_THRESHOLD_G = 3.2f
