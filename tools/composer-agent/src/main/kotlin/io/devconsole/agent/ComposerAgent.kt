package io.devconsole.agent

import java.net.URI

class ComposerAgent(
    deviceUrl: URI,
    token: String? = null,
    name: String,
    allowHosts: Set<String> = emptySet(),
    blockPrivate: Boolean = false,
    log: (String) -> Unit = {},
) {
    private val connection =
        AgentConnection(
            deviceUrl = deviceUrl,
            token = token,
            name = name,
            executor = RequestExecutor(HostPolicy(allowHosts, blockPrivate)),
            log = log,
        )

    fun run() = connection.run()

    fun stop() = connection.stop()

    companion object {
        fun exchangeSessionCode(
            deviceUrl: URI,
            code: String,
            label: String,
        ): String = SessionExchange.exchange(deviceUrl, code, label)
    }
}
