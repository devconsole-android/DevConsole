package io.devconsole.server.ktor

internal object DashboardAssets {
    fun index(): String = resource("/devconsole-web/index.html")

    fun css(): String = resource("/devconsole-web/dashboard.css")

    fun js(): String = resource("/devconsole-web/dashboard.js")

    private fun resource(path: String): String =
        requireNotNull(DashboardAssets::class.java.getResourceAsStream(path)) {
            "DevConsole dashboard asset is missing: $path"
        }.bufferedReader().use { it.readText() }
}
