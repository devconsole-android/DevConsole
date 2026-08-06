/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Minimal, declared-in-manifest host window for [ScreenshotCaptureInstrumentedTest]. A distinct,
 * non-black background makes an accidental blank/black capture visibly wrong rather than silently
 * matching a coincidentally black default background.
 */
internal class ScreenshotProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(PROBE_RED, PROBE_GREEN, PROBE_BLUE))
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
            }
        setContentView(root)
    }

    private companion object {
        const val PROBE_RED = 30
        const val PROBE_GREEN = 144
        const val PROBE_BLUE = 255
    }
}
