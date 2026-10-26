package io.devconsole.agent

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val USAGE =
    """
    devconsole-agent ${AgentConnection.AGENT_VERSION} -- run DevConsole Composer requests from this computer

    Usage: devconsole-agent --device <url> [--code <session code> | --token <access token>] [options]

      --device <url>      DevConsole server on the phone, e.g. http://localhost:4321
                          (after `adb forward tcp:4321 tcp:4321`, or the phone's LAN address)
      --code <code>       One-time session code from the dashboard's Overview page
      --token <token>     An existing access token instead of a code
      --name <label>      How this computer is named in the dashboard's "Run on" menu
      --allow-host <h>    Only run requests to this host (repeatable, or comma separated)
      --block-private     Refuse hosts that resolve to localhost / private / VPN addresses

    Without --code/--token the agent connects as an open-mode client, which only works when
    the app runs DevConsole in open mode.
    """.trimIndent()

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun log(message: String) = println("[${LocalTime.now().format(CLOCK)}] $message")

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(USAGE)
        return
    }
    val options =
        try {
            AgentOptions.parse(args)
        } catch (usage: UsageException) {
            System.err.println("${usage.message}\n\n$USAGE")
            exitProcess(EXIT_USAGE)
        }
    val agent =
        try {
            val token =
                options.token
                    ?: options.code?.let { ComposerAgent.exchangeSessionCode(options.deviceUrl, it, options.name) }
            ComposerAgent(
                deviceUrl = options.deviceUrl,
                token = token,
                name = options.name,
                allowHosts = options.allowHosts,
                blockPrivate = options.blockPrivate,
                log = ::log,
            )
        } catch (fatal: AgentFatalException) {
            System.err.println(fatal.message)
            exitProcess(EXIT_FATAL)
        }
    Runtime.getRuntime().addShutdownHook(Thread(agent::stop))
    log("DevConsole agent ${AgentConnection.AGENT_VERSION}: dashboard requests will run on this computer.")
    try {
        agent.run()
    } catch (fatal: AgentFatalException) {
        System.err.println(fatal.message)
        exitProcess(EXIT_FATAL)
    }
}

private const val EXIT_FATAL = 1
private const val EXIT_USAGE = 2
