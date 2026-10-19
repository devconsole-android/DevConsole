package io.devconsole.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.contentcapture.ContentCaptureManager

class DevConsoleActivity : ComponentActivity() {
    /** The host's value, put back when the inspector leaves the foreground. */
    private var hostContentCaptureEnabled: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DevConsoleWorkspace() }
    }

    // Content capture re-walks the semantics tree on every change (~1/5 of main-thread time); pause it
    // while this full-screen inspector is in front. The flag is process-wide, so onPause restores it.
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onResume() {
        super.onResume()
        hostContentCaptureEnabled = ContentCaptureManager.isEnabled
        ContentCaptureManager.isEnabled = false
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onPause() {
        hostContentCaptureEnabled?.let { ContentCaptureManager.isEnabled = it }
        hostContentCaptureEnabled = null
        super.onPause()
    }
}
