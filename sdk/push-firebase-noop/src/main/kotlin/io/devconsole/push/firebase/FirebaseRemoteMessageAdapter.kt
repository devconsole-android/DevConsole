package io.devconsole.push.firebase

import io.devconsole.push.PushInput

/** Protected-build adapter: does not reflect on or serialize the supplied Firebase message. */
class FirebaseRemoteMessageAdapter {
    fun toPushInput(
        @Suppress("UNUSED_PARAMETER") remoteMessage: Any,
    ): PushInput = PushInput(provider = "fcm", data = emptyMap(), source = "disabled-build")
}
