package io.devconsole

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import io.devconsole.api.EventSeverity

internal class SessionMarkerRecorder(
    private val bridge: CaptureTimelineBridge,
) {
    fun sdkStarted(bindingMode: String) =
        emit(
            "system.sdk.started",
            "DevConsole server started",
            "{\"bindingMode\":\"${bindingMode.safeMarkerLabel()}\"}",
        )

    fun sdkStopped(reason: String) =
        emit(
            "system.sdk.stopped",
            "DevConsole server stopped",
            "{\"reason\":\"${reason.safeMarkerLabel()}\"}",
        )

    fun appForeground() = emit("system.app.foreground", "Application entered foreground")

    fun appBackground() = emit("system.app.background", "Application entered background")

    fun connectivityChanged(
        available: Boolean,
        transports: Set<String>,
        validated: Boolean,
    ) {
        val safeTransports =
            transports
                .map(String::safeMarkerLabel)
                .sorted()
                .joinToString(",") { "\"$it\"" }
        emit(
            "system.connectivity.changed",
            if (available) "Connectivity available" else "Connectivity unavailable",
            "{\"available\":$available,\"validated\":$validated,\"transports\":[$safeTransports]}",
        )
    }

    fun configurationChanged(
        orientation: String,
        uiMode: Int,
        densityDpi: Int,
        fontScale: Float,
    ) = emit(
        "system.configuration.changed",
        "Application configuration changed",
        "{\"orientation\":\"${orientation.safeMarkerLabel()}\",\"uiMode\":$uiMode," +
            "\"densityDpi\":$densityDpi,\"fontScale\":$fontScale}",
    )

    fun dataDropped(
        pluginId: String,
        count: Long,
    ) = emit(
        "system.data.dropped",
        "DevConsole dropped buffered diagnostic data",
        "{\"pluginId\":\"${pluginId.safeMarkerLabel()}\",\"count\":${count.coerceAtLeast(0)}}",
        EventSeverity.WARN,
    )

    fun storageRecovered() =
        emit(
            "system.storage.recovered",
            "DevConsole recovered corrupt diagnostic storage from a private backup",
            severity = EventSeverity.WARN,
        )

    private fun emit(
        type: String,
        summary: String,
        tagsJson: String = "{}",
        severity: EventSeverity = EventSeverity.INFO,
    ) {
        bridge.emit(
            pluginId = "system",
            type = type,
            severity = severity,
            summary = summary,
            tagsJson = tagsJson,
        )
    }
}

/** Android lifecycle/network callbacks feeding only coarse, non-sensitive state into markers. */
internal class AndroidSessionMarkerMonitor(
    private val application: Application,
    private val recorder: SessionMarkerRecorder,
) : Application.ActivityLifecycleCallbacks,
    ComponentCallbacks2 {
    private var running = false
    private var startedActivities = 0
    private var foreground = false
    private var lastConnectivity: ConnectivitySnapshot? = null
    private val connectivityManager =
        application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishConnectivity()

            override fun onLost(network: Network) = publishConnectivity()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = publishConnectivity()
        }

    fun start(bindingMode: String) {
        if (running) return
        running = true
        recorder.sdkStarted(bindingMode)
        application.registerActivityLifecycleCallbacks(this)
        application.registerComponentCallbacks(this)
        detectCurrentForeground()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest
                        .Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            }
            publishConnectivity()
        }
    }

    fun stop(reason: String) {
        if (!running) return
        recorder.sdkStopped(reason)
        running = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        application.unregisterActivityLifecycleCallbacks(this)
        application.unregisterComponentCallbacks(this)
        startedActivities = 0
        foreground = false
        lastConnectivity = null
    }

    fun runtimeConfigurationChanged() {
        if (running) publishConfiguration(application.resources.configuration)
    }

    fun dataDropped(
        pluginId: String,
        count: Long,
    ) {
        if (running) recorder.dataDropped(pluginId, count)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0 && !foreground) {
            foreground = true
            recorder.appForeground()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && foreground && !activity.isChangingConfigurations) {
            foreground = false
            recorder.appBackground()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        publishConfiguration(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && foreground) {
            foreground = false
            recorder.appBackground()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onLowMemory() = Unit

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun detectCurrentForeground() {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            foreground = true
            recorder.appForeground()
        }
    }

    private fun publishConfiguration(configuration: Configuration) {
        recorder.configurationChanged(
            orientation =
                when (configuration.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                    Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
                    else -> "UNDEFINED"
                },
            uiMode = configuration.uiMode,
            densityDpi = configuration.densityDpi,
            fontScale = configuration.fontScale,
        )
    }

    private fun publishConnectivity() {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        val snapshot =
            ConnectivitySnapshot(
                available = capabilities != null,
                transports =
                    buildSet {
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ==
                            true
                        ) {
                            add("BLUETOOTH")
                        }
                    },
                validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
        if (snapshot == lastConnectivity) return
        lastConnectivity = snapshot
        recorder.connectivityChanged(snapshot.available, snapshot.transports, snapshot.validated)
    }

    private data class ConnectivitySnapshot(
        val available: Boolean,
        val transports: Set<String>,
        val validated: Boolean,
    )
}

private fun String.safeMarkerLabel(): String =
    take(128)
        .map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
        .joinToString("")
