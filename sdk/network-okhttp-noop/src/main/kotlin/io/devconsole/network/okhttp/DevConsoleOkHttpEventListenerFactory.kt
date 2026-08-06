/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.network.okhttp

import okhttp3.Call
import okhttp3.EventListener

/** Protected-build adapter: forwards to [delegate], if any, and never allocates capture state. */
class DevConsoleOkHttpEventListenerFactory
    @JvmOverloads
    constructor(
        private val delegate: EventListener.Factory? = null,
    ) : EventListener.Factory {
        override fun create(call: Call): EventListener = delegate?.create(call) ?: EventListener.NONE
    }
