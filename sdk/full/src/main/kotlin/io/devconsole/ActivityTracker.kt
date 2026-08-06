/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the currently resumed `Activity` so [ScreenshotCapture] has a window to capture without the
 * SDK ever holding an `Activity` alive itself.
 *
 * Only ever holds a [WeakReference] -- the field is never a strong reference to an `Activity`, by
 * construction. [currentActivity] returns null once nothing is resumed (covers backgrounding as well
 * as process-wide teardown), which is exactly the signal [io.devconsole.api.ScreenshotResult.NoForegroundActivity]
 * needs.
 *
 * That guarantee covers only this class's own field, not the SDK end to end: once [currentActivity] is
 * read, an in-flight [io.devconsole.ScreenshotCapture] holds the returned `Activity` (and its `Window`,
 * decor view, and capture bitmap) strongly reachable from its suspended coroutine for as long as that
 * one capture is outstanding. [io.devconsole.ScreenshotCapture] bounds that window with its own timeout
 * and recycles the bitmap on every exit path; this class has no part in that and cannot extend the
 * guarantee to it.
 */
internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumedActivity: WeakReference<Activity> = WeakReference(null)

    /** The currently resumed `Activity`, or null when nothing is in the foreground. */
    fun currentActivity(): Activity? = resumedActivity.get()

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        clearIfCurrent(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        clearIfCurrent(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        clearIfCurrent(activity)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    /** A no-longer-resumed Activity must stop being reported as the capture target immediately. */
    private fun clearIfCurrent(activity: Activity) {
        if (resumedActivity.get() === activity) {
            resumedActivity = WeakReference(null)
        }
    }
}
