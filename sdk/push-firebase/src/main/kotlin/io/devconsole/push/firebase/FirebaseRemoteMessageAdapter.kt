package io.devconsole.push.firebase

import io.devconsole.push.PushInput
import io.devconsole.push.PushNotification

/**
 * Optional Firebase bridge with no Firebase compile-time dependency. Consumers may pass a
 * `com.google.firebase.messaging.RemoteMessage`; reflection keeps Firebase absent elsewhere.
 */
class FirebaseRemoteMessageAdapter {
    fun toPushInput(remoteMessage: Any): PushInput {
        val data = remoteMessage.call("getData") as? Map<*, *> ?: emptyMap<Any, Any>()
        val notification =
            remoteMessage.call("getNotification")?.let { source ->
                PushNotification(
                    title = source.call("getTitle") as? String,
                    body = source.call("getBody") as? String,
                    channelId = source.call("getChannelId") as? String,
                    imageUrl = source.call("getImageUrl")?.toString(),
                )
            }
        return PushInput(
            provider = "fcm",
            data = data.entries.associate { (key, value) -> key.toString() to value.toString() },
            messageId = remoteMessage.call("getMessageId") as? String,
            source = "firebase",
            sentAtEpochMs = (remoteMessage.call("getSentTime") as? Number)?.toLong(),
            notification = notification,
            rawMetadata =
                mapOfNotNull(
                    "from" to remoteMessage.call("getFrom") as? String,
                    "collapseKey" to remoteMessage.call("getCollapseKey") as? String,
                ),
        )
    }

    private fun Any.call(name: String): Any? =
        runCatching {
            javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull()

    private fun mapOfNotNull(vararg entries: Pair<String, String?>): Map<String, String> =
        entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}
