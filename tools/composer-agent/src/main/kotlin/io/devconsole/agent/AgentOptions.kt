package io.devconsole.agent

import java.net.InetAddress
import java.net.URI

internal class UsageException(
    message: String,
) : RuntimeException(message)

internal class AgentOptions(
    val deviceUrl: URI,
    val code: String?,
    val token: String?,
    val name: String,
    val allowHosts: Set<String>,
    val blockPrivate: Boolean,
) {
    private class Builder {
        var device: String? = null
        var code: String? = null
        var token: String? = null
        var name: String? = null
        val allow = mutableSetOf<String>()
        var blockPrivate = false

        fun apply(
            flag: String,
            value: () -> String,
        ) {
            when (flag) {
                "--device" -> device = value()
                "--code" -> code = value()
                "--token" -> token = value()
                "--name" -> name = value()
                "--allow-host" -> allow += value().split(',').map(String::trim).filter(String::isNotEmpty)
                "--block-private" -> blockPrivate = true
                else -> throw UsageException("Unknown option: $flag")
            }
        }

        fun build(): AgentOptions {
            val uri = parseDeviceUrl(device)
            if (code != null && token != null) throw UsageException("Use either --code or --token, not both")
            val label = name?.takeIf(String::isNotBlank) ?: defaultName()
            return AgentOptions(uri, code, token, label, allow, blockPrivate)
        }
    }

    companion object {
        fun parse(args: Array<String>): AgentOptions {
            val builder = Builder()
            var index = 0
            while (index < args.size) {
                val flag = args[index]
                builder.apply(flag) { args.getOrNull(++index) ?: throw UsageException("$flag needs a value") }
                index++
            }
            return builder.build()
        }

        private fun parseDeviceUrl(device: String?): URI {
            if (device == null) throw UsageException("--device is required")
            val uri = runCatching { URI(device) }.getOrNull()
            return uri?.takeIf { it.host != null && it.scheme?.lowercase() in setOf("http", "https") }
                ?: throw UsageException("--device must look like http://localhost:4321 (got: $device)")
        }

        private fun defaultName(): String =
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "computer"
    }
}
