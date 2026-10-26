package io.devconsole.agent

import java.net.URLDecoder
import java.net.URLEncoder

internal class AgentFrame(
    private val pairs: List<Pair<String, String>>,
) {
    operator fun get(name: String): String? = pairs.firstOrNull { it.first == name }?.second

    fun getAll(name: String): List<String> = pairs.filter { it.first == name }.map { it.second }

    companion object {
        const val HELLO = "agent.hello"
        const val WELCOME = "agent.welcome"
        const val EXECUTE = "agent.execute"
        const val RESULT = "agent.result"
        const val ERROR = "agent.error"

        const val ERROR_HOST_REJECTED = "HOST_REJECTED"
        const val ERROR_FAILED = "FAILED"

        fun encode(vararg pairs: Pair<String, String>): String = encode(pairs.toList())

        fun encode(pairs: List<Pair<String, String>>): String =
            pairs.joinToString("&") { (name, value) -> "${name.urlEncode()}=${value.urlEncode()}" }

        fun decode(text: String): AgentFrame =
            AgentFrame(
                text
                    .split('&')
                    .filter(String::isNotEmpty)
                    .map { field ->
                        val index = field.indexOf('=')
                        if (index < 0) {
                            field.urlDecode() to ""
                        } else {
                            field.substring(0, index).urlDecode() to field.substring(index + 1).urlDecode()
                        }
                    },
            )

        private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

        private fun String.urlDecode(): String = URLDecoder.decode(this, "UTF-8")
    }
}
