/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole

/**
 * Single-method result callback for the Java-friendly `DevConsole.*Async` methods
 * ([DevConsole.startBrowserAsync], [DevConsole.stopAsync], [DevConsole.captureScreenshotAsync]).
 *
 * This is a plain `fun interface` rather than `java.util.function.Consumer` so the async facade works
 * at `minSdk 23` without requiring consumers to enable core-library desugaring (`Consumer` is API 24).
 * Java callers pass a lambda exactly as before (`result -> ...`); Kotlin callers use trailing-lambda
 * SAM conversion (`{ result -> ... }`). Kotlin code should prefer the `suspend` counterparts.
 */
fun interface DevConsoleCallback<T> {
    fun onResult(value: T)
}
