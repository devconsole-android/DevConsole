package io.devconsole.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Lightweight XML launcher that has no dependency on Compose, lifecycle-runtime, or the full SDK.
 * The host owns start/stop actions and supplies its runtime state flow. `DevConsoleState.Running`
 * carries no payload, so the host must call [setEndpoint] with the `StartResult.Started.endpoint`
 * its own `onStart` receives for the running address to be shown; it's cleared automatically the
 * next time the panel renders a non-running state.
 *
 * This module (`ui-views`), and this view, are a **Start/Stop launcher only** -- there is no
 * network/socket/data inspection UI here. Once the server is running, inspection happens in the
 * web dashboard (open the connect URL in a browser) or, for an in-app surface, via `ui-compose`'s
 * inspector (`DevConsole.open(context)`). Don't reach for this module expecting an embeddable
 * capture viewer; it only ever renders status text, start/stop buttons, and (when applicable)
 * a keep-alive notification prompt.
 */
class DevConsolePanelView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private val status = TextView(context)
        private val warning = TextView(context)
        private val start = Button(context).apply { text = "Start DevConsole" }
        private val stop = Button(context).apply { text = "Stop DevConsole" }
        private val keepAliveNotice =
            TextView(context).apply {
                text = "Allow notifications so the server can stay alive in the background"
                visibility = View.GONE
            }
        private val keepAliveAllow =
            Button(context).apply {
                text = "Allow"
                visibility = View.GONE
            }
        private val keepAliveDismiss =
            Button(context).apply {
                text = "Dismiss"
                visibility = View.GONE
            }
        private var keepAliveNoticeDismissed = false
        private var lastState: DevConsoleState = DevConsoleState.Uninitialized
        private var endpoint: BrowserEndpoint? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.START
            status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            warning.visibility = View.GONE
            addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(warning, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(keepAliveNotice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                LinearLayout(context).apply {
                    orientation = actionOrientation(resources.configuration.fontScale)
                    gravity = Gravity.CENTER_VERTICAL
                    keepAliveAllow.minimumHeight = minimumTouchTargetPx()
                    keepAliveDismiss.minimumHeight = minimumTouchTargetPx()
                    val buttonWidth = if (orientation == HORIZONTAL) 0 else LayoutParams.MATCH_PARENT
                    val buttonWeight = if (orientation == HORIZONTAL) 1f else 0f
                    addView(keepAliveAllow, LayoutParams(buttonWidth, LayoutParams.WRAP_CONTENT, buttonWeight))
                    addView(keepAliveDismiss, LayoutParams(buttonWidth, LayoutParams.WRAP_CONTENT, buttonWeight))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = actionOrientation(resources.configuration.fontScale)
                    gravity = Gravity.CENTER_VERTICAL
                    start.minimumHeight = minimumTouchTargetPx()
                    stop.minimumHeight = minimumTouchTargetPx()
                    val buttonWidth = if (orientation == HORIZONTAL) 0 else LayoutParams.MATCH_PARENT
                    val buttonWeight = if (orientation == HORIZONTAL) 1f else 0f
                    addView(start, LayoutParams(buttonWidth, LayoutParams.WRAP_CONTENT, buttonWeight))
                    addView(stop, LayoutParams(buttonWidth, LayoutParams.WRAP_CONTENT, buttonWeight))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            keepAliveAllow.setOnClickListener {
                context.findActivity()?.requestPermissions(
                    arrayOf(PERMISSION_POST_NOTIFICATIONS),
                    KEEP_ALIVE_PERMISSION_REQUEST_CODE,
                )
                hideKeepAliveNotice()
            }
            keepAliveDismiss.setOnClickListener {
                keepAliveNoticeDismissed = true
                hideKeepAliveNotice()
            }
            render(DevConsoleState.Uninitialized)
        }

        fun bind(
            state: StateFlow<DevConsoleState>,
            scope: CoroutineScope,
            onStart: () -> Unit,
            onStop: () -> Unit,
        ): Job {
            start.setOnClickListener { onStart() }
            stop.setOnClickListener { onStop() }
            render(state.value)
            return scope.launch {
                state.collect { next -> post { render(next) } }
            }
        }

        /** Call once [onStart] resolves to `StartResult.Started` so the panel can show where it bound. */
        fun setEndpoint(endpoint: BrowserEndpoint?) {
            this.endpoint = endpoint
            post { render(lastState) }
        }

        fun render(state: DevConsoleState) {
            lastState = state
            if (state !is DevConsoleState.Running) endpoint = null
            status.text = DevConsoleStatusText.forState(state, endpoint)
            warning.text = lanTransportWarning(endpoint).orEmpty()
            warning.visibility = if (warning.text.isEmpty()) View.GONE else View.VISIBLE
            refreshKeepAliveNotice(state is DevConsoleState.Running)
            start.isEnabled =
                state is DevConsoleState.Initialized ||
                state is DevConsoleState.Stopped ||
                state is DevConsoleState.PermissionRequired
            stop.isEnabled = state is DevConsoleState.Running || state is DevConsoleState.Starting
        }

        private fun refreshKeepAliveNotice(serverRunning: Boolean) {
            val show = !keepAliveNoticeDismissed && keepAliveNoticeNeeded(context, serverRunning)
            val visibility = if (show) View.VISIBLE else View.GONE
            keepAliveNotice.visibility = visibility
            keepAliveAllow.visibility = visibility
            keepAliveDismiss.visibility = visibility
        }

        private fun hideKeepAliveNotice() {
            keepAliveNotice.visibility = View.GONE
            keepAliveAllow.visibility = View.GONE
            keepAliveDismiss.visibility = View.GONE
        }

        private fun minimumTouchTargetPx(): Int = (MINIMUM_TOUCH_TARGET_DP * resources.displayMetrics.density).toInt()

        private companion object {
            const val KEEP_ALIVE_PERMISSION_REQUEST_CODE = 0xDC1
        }
    }

internal const val LOCAL_NETWORK_HTTP_WARNING =
    "This debugging session uses local-network HTTP. Other participants on an untrusted network " +
        "may observe or modify traffic. Use ADB localhost mode or a trusted isolated network for sensitive testing."

internal fun lanTransportWarning(endpoint: BrowserEndpoint?): String? =
    LOCAL_NETWORK_HTTP_WARNING.takeIf { endpoint?.bindingMode == BindingMode.LAN }

internal fun actionOrientation(fontScale: Float): Int =
    if (fontScale >= LARGE_TEXT_STACK_THRESHOLD) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

private const val MINIMUM_TOUCH_TARGET_DP = 48
private const val LARGE_TEXT_STACK_THRESHOLD = 1.3f

object DevConsoleStatusText {
    fun forState(
        state: DevConsoleState,
        endpoint: BrowserEndpoint? = null,
    ): String =
        when (state) {
            DevConsoleState.Uninitialized -> "DevConsole is not initialized"
            DevConsoleState.DisabledForBuild -> "DevConsole is disabled for this build"
            DevConsoleState.Initialized, DevConsoleState.Stopped -> "DevConsole is ready"
            DevConsoleState.PermissionRequired -> "Local network permission is required"
            DevConsoleState.Starting -> "Starting DevConsole"
            DevConsoleState.Running ->
                if (endpoint != null) {
                    "DevConsole server is running at ${endpoint.host}:${endpoint.port}"
                } else {
                    "DevConsole server is running"
                }
            DevConsoleState.Stopping -> "Stopping DevConsole"
            is DevConsoleState.Failed -> "DevConsole failed: ${state.message}"
        }
}
