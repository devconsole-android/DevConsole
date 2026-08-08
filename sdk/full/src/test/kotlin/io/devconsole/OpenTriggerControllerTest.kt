/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole

import android.app.Activity
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.InitResult
import io.devconsole.api.OpenTriggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenTriggerControllerTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val sensorManager: SensorManager = application.getSystemService(SensorManager::class.java)
    private val opened = mutableListOf<Activity>()
    private var nowMs = 0L
    private val controller = OpenTriggerController(application, { opened += it }, { nowMs })

    private val accelerometer: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)

    @Before
    fun addAccelerometer() {
        shadowOf(sensorManager).addSensor(accelerometer)
    }

    private fun floatingButton(activity: Activity): View? =
        (activity.window.decorView as ViewGroup).findViewWithTag(OpenTriggerController.BUTTON_TAG)

    private fun sendAcceleration(
        magnitudeG: Float,
        atMs: Long,
    ) {
        nowMs = atMs
        val event =
            SensorEventBuilder
                .newBuilder()
                .setSensor(accelerometer)
                .setValues(floatArrayOf(magnitudeG * SensorManager.GRAVITY_EARTH, 0f, 0f))
                .build()
        shadowOf(sensorManager).sendSensorEventToListeners(event)
    }

    private fun touch(
        view: View,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `defaults leave a resumed activity untouched`() {
        controller.reconfigure(OpenTriggers())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        controller.onActivityResumed(activity)

        assertFalse(shadowOf(sensorManager).hasListener(controller))
        assertNull(floatingButton(activity))
    }

    @Test
    fun `floating button attaches on resume and detaches on pause`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        controller.onActivityResumed(activity)
        val button = floatingButton(activity)
        assertNotNull(button)
        assertEquals(
            OpenTriggerController.BUTTON_CONTENT_DESCRIPTION,
            button?.contentDescription?.toString(),
        )

        controller.onActivityPaused(activity)
        assertNull(floatingButton(activity))
    }

    /** The button is the DevConsole mark itself, so a missing or unresolvable drawable is a bug. */
    @Test
    fun `floating button renders the devconsole mark and clips its shadow to the artwork`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        controller.onActivityResumed(activity)
        val button = floatingButton(activity) as ImageView

        assertNotNull(button.drawable)
        assertTrue(button.clipToOutline)
        assertTrue(button.outlineProvider is RoundedSquareOutline)
    }

    @Test
    fun `tapping the button opens the inspector`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        val button = floatingButton(activity)!!

        touch(button, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(button, MotionEvent.ACTION_UP, 10f, 10f)

        assertEquals(listOf<Activity>(activity), opened)
    }

    @Test
    fun `dragging moves the button without opening the inspector`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        val button = floatingButton(activity)!!

        touch(button, MotionEvent.ACTION_DOWN, 500f, 900f)
        touch(button, MotionEvent.ACTION_MOVE, 620f, 1030f)
        touch(button, MotionEvent.ACTION_UP, 620f, 1030f)

        assertEquals(120f, button.x, 0f)
        assertEquals(130f, button.y, 0f)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `dragged position survives across activities`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(first)
        val firstButton = floatingButton(first)!!
        touch(firstButton, MotionEvent.ACTION_DOWN, 500f, 900f)
        touch(firstButton, MotionEvent.ACTION_MOVE, 620f, 1030f)
        touch(firstButton, MotionEvent.ACTION_UP, 620f, 1030f)
        controller.onActivityPaused(first)

        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(second)

        val secondButton = floatingButton(second)!!
        assertEquals(120f, secondButton.x, 0f)
        assertEquals(130f, secondButton.y, 0f)
    }

    @Test
    fun `shake past threshold opens the inspector and cooldown blocks repeats`() {
        controller.reconfigure(OpenTriggers(shakeToOpen = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        assertTrue(shadowOf(sensorManager).hasListener(controller))

        // One spike is not a shake.
        sendAcceleration(3.0f, atMs = 1_000)
        assertTrue(opened.isEmpty())
        // Its partner within 400ms is.
        sendAcceleration(3.0f, atMs = 1_200)
        assertEquals(1, opened.size)
        // A full two-peak shake inside the 1500ms cooldown must not fire.
        sendAcceleration(3.0f, atMs = 1_400)
        sendAcceleration(3.0f, atMs = 1_600)
        assertEquals(1, opened.size)
        // After the cooldown lapses the next two-peak shake fires again.
        sendAcceleration(3.0f, atMs = 3_000)
        sendAcceleration(3.0f, atMs = 3_200)
        assertEquals(2, opened.size)
    }

    @Test
    fun `shakes below the configured intensity threshold never open the inspector`() {
        controller.reconfigure(OpenTriggers(shakeToOpen = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)

        // 2.0g clears LIGHT (1.9g) but not the configured MEDIUM (2.5g).
        sendAcceleration(2.0f, atMs = 1_000)
        sendAcceleration(2.0f, atMs = 1_200)

        assertTrue(opened.isEmpty())
    }

    @Test
    fun `pausing unregisters the sensor listener`() {
        controller.reconfigure(OpenTriggers(shakeToOpen = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        assertTrue(shadowOf(sensorManager).hasListener(controller))

        controller.onActivityPaused(activity)

        assertFalse(shadowOf(sensorManager).hasListener(controller))
    }

    @Test
    fun `the inspector activity itself never gets triggers`() {
        controller.reconfigure(OpenTriggers(shakeToOpen = true, floatingButton = true))
        // Loaded reflectively: ComponentActivity is not on this module's compile classpath, and
        // resolving by name also exercises the exact string the controller's skip compares against.
        @Suppress("UNCHECKED_CAST")
        val inspectorClass =
            Class.forName(OpenTriggerController.INSPECTOR_ACTIVITY_CLASS_NAME) as Class<Activity>
        val inspector = Robolectric.buildActivity(inspectorClass).get()

        controller.onActivityResumed(inspector)

        assertFalse(shadowOf(sensorManager).hasListener(controller))
        assertNull(floatingButton(inspector))
    }

    @Test
    fun `reconfiguring to disabled detaches the button and the sensor listener`() {
        controller.reconfigure(OpenTriggers(shakeToOpen = true, floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        assertTrue(shadowOf(sensorManager).hasListener(controller))
        assertNotNull(floatingButton(activity))

        controller.reconfigure(OpenTriggers.disabled())

        assertFalse(shadowOf(sensorManager).hasListener(controller))
        assertNull(floatingButton(activity))
    }

    private fun layout(
        activity: Activity,
        width: Int,
        height: Int,
    ) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
    }

    @Test
    fun `dragging cannot park the button off-screen`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        layout(activity, width = 1080, height = 1920)
        val button = floatingButton(activity)!!

        touch(button, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(button, MotionEvent.ACTION_MOVE, 5000f, 9000f)
        touch(button, MotionEvent.ACTION_UP, 5000f, 9000f)

        assertEquals((1080 - button.width).toFloat(), button.x, 0f)
        assertEquals((1920 - button.height).toFloat(), button.y, 0f)
    }

    @Test
    fun `a stored position is re-clamped inside a smaller window on re-attach`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(first)
        layout(first, width = 1080, height = 1920)
        val firstButton = floatingButton(first)!!
        touch(firstButton, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(firstButton, MotionEvent.ACTION_MOVE, 5000f, 9000f)
        touch(firstButton, MotionEvent.ACTION_UP, 5000f, 9000f)
        controller.onActivityPaused(first)

        // A landscape-sized window: the portrait-dragged position lies entirely outside it.
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(second)
        layout(second, width = 470, height = 320)

        val secondButton = floatingButton(second)!!
        assertTrue(secondButton.x <= (470 - secondButton.width).toFloat())
        assertTrue(secondButton.y <= (320 - secondButton.height).toFloat())
        assertTrue(secondButton.x >= 0f)
        assertTrue(secondButton.y >= 0f)
    }

    @Test
    fun `rapid double tap opens the inspector once`() {
        controller.reconfigure(OpenTriggers(floatingButton = true))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        val button = floatingButton(activity)!!

        nowMs = 1_000
        touch(button, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(button, MotionEvent.ACTION_UP, 10f, 10f)
        touch(button, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(button, MotionEvent.ACTION_UP, 10f, 10f)
        assertEquals(1, opened.size)

        nowMs = 1_000 + OpenTriggerController.OPEN_DEBOUNCE_MS
        touch(button, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(button, MotionEvent.ACTION_UP, 10f, 10f)
        assertEquals(2, opened.size)
    }

    @Test
    fun `pausing one of two resumed activities falls back instead of killing the sensor`() {
        controller.reconfigure(OpenTriggers(shakeToOpen = true))
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(first)
        controller.onActivityResumed(second)

        controller.onActivityPaused(second)

        assertTrue(shadowOf(sensorManager).hasListener(controller))
        sendAcceleration(3.0f, atMs = 1_000)
        sendAcceleration(3.0f, atMs = 1_200)
        assertEquals(listOf<Activity>(first), opened)
    }

    @Test
    fun `initialize wires openTriggers so a resumed activity gets the floating button`() {
        val provider = PlatformFacadeProvider()
        val config = DevConsoleConfig.default().withOpenTriggers(OpenTriggers(floatingButton = true))
        assertEquals(InitResult.Initialized, provider.initialize(application, config))

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertNotNull(floatingButton(activity))
    }
}
