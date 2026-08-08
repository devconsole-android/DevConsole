package io.devconsole.server.ktor

internal object DashboardAssets {
    fun index(): String = resource("/devconsole-web/index.html")

    fun css(): String = resource("/devconsole-web/dashboard.css")

    fun js(): String = resource("/devconsole-web/dashboard.js")

    /** The DevConsole mark, served as the dashboard's favicon. Read as bytes -- it is not text. */
    fun favicon(): ByteArray = binaryResource("/devconsole-web/favicon.webp")

    private fun resource(path: String): String = binaryResource(path).decodeToString()

    private fun binaryResource(path: String): ByteArray =
        requireNotNull(DashboardAssets::class.java.getResourceAsStream(path)) {
            "DevConsole dashboard asset is missing: $path"
        }.use { it.readBytes() }
}
