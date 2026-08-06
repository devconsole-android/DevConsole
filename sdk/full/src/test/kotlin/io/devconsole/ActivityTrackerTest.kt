/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Activity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityTrackerTest {
    @Test
    fun `no activity is current before anything resumes`() {
        val tracker = ActivityTracker()

        assertNull(tracker.currentActivity())
    }

    @Test
    fun `tracks the most recently resumed activity`() {
        val tracker = ActivityTracker()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        tracker.onActivityResumed(activity)

        assertSame(activity, tracker.currentActivity())
        controller.pause().stop().destroy()
    }

    @Test
    fun `pausing the tracked activity clears it, matching NoForegroundActivity semantics`() {
        val tracker = ActivityTracker()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        tracker.onActivityResumed(activity)

        tracker.onActivityPaused(activity)

        assertNull(tracker.currentActivity())
        controller.stop().destroy()
    }

    @Test
    fun `pausing a different activity does not clear the tracked one`() {
        val tracker = ActivityTracker()
        val firstController = Robolectric.buildActivity(Activity::class.java).setup()
        val secondController = Robolectric.buildActivity(Activity::class.java).create()
        val first = firstController.get()
        val second = secondController.get()
        tracker.onActivityResumed(first)

        tracker.onActivityPaused(second)

        assertSame(first, tracker.currentActivity())
        firstController.pause().stop().destroy()
        secondController.destroy()
    }

    /**
     * The leak test: once every strong reference the test itself holds is dropped, the tracker's
     * [WeakReference] must be clearable by the garbage collector. [ActivityTracker] never stores
     * anything but a [WeakReference], so if this activity is not otherwise reachable, it must not
     * survive a GC pass -- proving the tracker itself is not what would keep an Activity alive.
     *
     * Deliberately bypasses [Robolectric.buildActivity]: its [ActivityController] and the shadowed
     * activity-thread bookkeeping behind it keep their own reachability paths to the `Activity`
     * independent of anything under test here, which would make this a test of Robolectric's
     * internals rather than of [ActivityTracker]. A directly constructed `Activity` has none of
     * that -- the only thing that can keep it reachable is this test's own locals and whatever
     * [ActivityTracker] stores internally.
     */
    @Test
    fun `weak reference clears once no external strong reference to the activity remains`() {
        val tracker = ActivityTracker()
        var activity: Activity? = Activity()
        tracker.onActivityResumed(activity!!)
        assertSame(activity, tracker.currentActivity())

        val weaklyHeld = WeakReference(activity)
        activity = null

        assertNull("expected the tracked Activity to become collectible", awaitCleared(weaklyHeld))
    }

    /**
     * `System.gc()` is a hint, not a guarantee -- so this retries with fresh garbage allocated
     * between attempts (a common, practical way to make a JVM's collector actually run) rather than
     * asserting after a single call, which would be flaky rather than a real leak test.
     */
    private fun awaitCleared(reference: WeakReference<*>): Any? {
        repeat(MAX_GC_ATTEMPTS) {
            if (reference.get() == null) return null
            allocateGarbageToEncourageCollection()
            System.gc()
            System.runFinalization()
        }
        return reference.get()
    }

    /** A JVM's `System.gc()` is only a hint; churning garbage between attempts makes it more likely to actually run. */
    private fun allocateGarbageToEncourageCollection() {
        var total = 0
        repeat(GARBAGE_ALLOCATIONS) { total += ByteArray(ALLOCATION_CHURN_BYTES).size }
        check(total > 0)
    }

    private companion object {
        const val MAX_GC_ATTEMPTS = 20
        const val GARBAGE_ALLOCATIONS = 8
        const val ALLOCATION_CHURN_BYTES = 100_000
    }
}
