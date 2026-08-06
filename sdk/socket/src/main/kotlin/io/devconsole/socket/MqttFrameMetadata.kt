/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket

/**
 * MQTT topic/qos/retained metadata carried in [SocketMessage.contentType] rather than as new fields
 * on [SocketMessage] -- see the design spec's "MQTT capture reuses the socket pipeline" section. The
 * encoding is hand-rolled and dependency-free (no JSON/URL-encoding library pulled into `sdk:socket`
 * just for this): `"application/mqtt; topic=<enc>; qos=<n>; retained=<bool>"`, where `<enc>` escapes
 * only the three characters that would otherwise break this format's own delimiters.
 *
 * Every parser here is fail-soft: malformed input, a non-MQTT content type, or null all return null
 * rather than throw, matching this module's fail-open recording discipline.
 */
object MqttFrameMetadata {
    const val CONTENT_TYPE: String = "application/mqtt"

    private const val TOPIC_KEY = "topic"
    private const val QOS_KEY = "qos"
    private const val RETAINED_KEY = "retained"
    private const val MIN_QOS = 0
    private const val MAX_QOS = 2

    @JvmStatic
    @JvmOverloads
    fun format(
        topic: String,
        qos: Int = 0,
        retained: Boolean = false,
    ): String = "$CONTENT_TYPE; $TOPIC_KEY=${topic.encodeSegment()}; $QOS_KEY=$qos; $RETAINED_KEY=$retained"

    @JvmStatic
    fun isMqtt(contentType: String?): Boolean {
        val trimmed = contentType?.trim() ?: return false
        return trimmed.equals(CONTENT_TYPE, ignoreCase = true) ||
            trimmed.startsWith("$CONTENT_TYPE;", ignoreCase = true)
    }

    @JvmStatic
    fun topic(contentType: String?): String? = segment(contentType, TOPIC_KEY)?.decodeSegment()

    @JvmStatic
    fun qos(contentType: String?): Int? {
        val value = segment(contentType, QOS_KEY)?.decodeSegment()?.toIntOrNull() ?: return null
        return value.takeIf { it in MIN_QOS..MAX_QOS }
    }

    @JvmStatic
    fun retained(contentType: String?): Boolean? =
        when (segment(contentType, RETAINED_KEY)?.decodeSegment()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun segment(
        contentType: String?,
        key: String,
    ): String? {
        if (!isMqtt(contentType)) return null
        val prefix = "$key="
        return contentType
            ?.split(';')
            ?.drop(1)
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
    }

    /** `%` first, then the two delimiter characters this format's own segments use. */
    private fun String.encodeSegment(): String = replace("%", "%25").replace(";", "%3B").replace("=", "%3D")

    /** Reverse of [encodeSegment]: the delimiter escapes first, `%` last, undoing double-encoding correctly. */
    private fun String.decodeSegment(): String = replace("%3B", ";").replace("%3D", "=").replace("%25", "%")
}
