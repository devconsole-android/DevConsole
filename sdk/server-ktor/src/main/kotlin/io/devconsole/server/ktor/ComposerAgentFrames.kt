package io.devconsole.server.ktor

import io.ktor.http.formUrlEncode

internal object ComposerAgentFrames {
    const val HELLO = "agent.hello"
    const val WELCOME = "agent.welcome"
    const val EXECUTE = "agent.execute"
    const val RESULT = "agent.result"
    const val ERROR = "agent.error"

    const val ERROR_HOST_REJECTED = "HOST_REJECTED"

    fun encode(vararg fields: Pair<String, String>): String = fields.toList().formUrlEncode()
}
