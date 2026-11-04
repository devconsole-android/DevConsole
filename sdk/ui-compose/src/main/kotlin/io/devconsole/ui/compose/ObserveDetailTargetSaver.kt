/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

private const val TAG_NONE = "none"
private const val TAG_NET = "net"
private const val TAG_FRAME = "frame"
private const val TAG_PUSH = "push"
private const val TAG_LOG = "log"
private const val TAG_CRASH = "crash"
private const val TAG_REMOTE_CONFIG = "rc"
private const val PUSH_RECEIVED_AT_INDEX = 3

/** Keeps an open capture detail across rotation: a type tag plus its Bundle-friendly identity fields. */
internal val ObserveDetailTargetSaver: Saver<ObserveDetailTarget?, Any> =
    listSaver(
        save = { target -> target.toSavedFields() },
        restore = { saved -> saved.toDetailTarget() },
    )

private fun ObserveDetailTarget?.toSavedFields(): List<Any?> =
    when (this) {
        null -> listOf(TAG_NONE)
        is ObserveDetailTarget.Net -> listOf(TAG_NET, transactionId)
        is ObserveDetailTarget.Frame -> listOf(TAG_FRAME, socketId, timestampEpochMs)
        is ObserveDetailTarget.Push -> listOf(TAG_PUSH, provider, messageId, receivedAtEpochMs)
        is ObserveDetailTarget.Log -> listOf(TAG_LOG, logId)
        is ObserveDetailTarget.Crash -> listOf(TAG_CRASH, crashId)
        is ObserveDetailTarget.RemoteConfigKey -> listOf(TAG_REMOTE_CONFIG, providerId, key)
    }

private fun List<Any?>.toDetailTarget(): ObserveDetailTarget? =
    when (firstOrNull()) {
        TAG_NET -> ObserveDetailTarget.Net(get(1) as String)
        TAG_FRAME -> ObserveDetailTarget.Frame(get(1) as String, get(2) as Long)
        TAG_PUSH -> ObserveDetailTarget.Push(get(1) as String, get(2) as String?, get(PUSH_RECEIVED_AT_INDEX) as Long)
        TAG_LOG -> ObserveDetailTarget.Log(get(1) as String)
        TAG_CRASH -> ObserveDetailTarget.Crash(get(1) as String)
        TAG_REMOTE_CONFIG -> ObserveDetailTarget.RemoteConfigKey(get(1) as String, get(2) as String)
        else -> null
    }
