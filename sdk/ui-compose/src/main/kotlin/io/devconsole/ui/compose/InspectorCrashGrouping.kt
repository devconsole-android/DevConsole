/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

private val FRAMEWORK_PACKAGE_PREFIXES =
    listOf(
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "android.",
        "androidx.",
        "com.android.",
        "dalvik.",
        "libcore.",
        "sun.",
    )

/** A group of crashes sharing the same [crashFingerprint], ordered with the [newest] crash first. */
internal data class InspectorCrashGroup(
    val fingerprint: String,
    val newest: InspectorCrashUi,
    val count: Int,
    val lastSeenEpochMs: Long,
    val crashes: List<InspectorCrashUi>,
)

/**
 * Computes a stable grouping fingerprint for [crash]:
 * - Exception class (first line of `stackTrace` up to `':'`, or `summary`'s leading class name).
 * - First stack frame line (`"at ..."`) whose package does not start with any framework prefix
 *   (`java.`, `javax.`, `kotlin.`, `kotlinx.`, `android.`, `androidx.`, `com.android.`, `dalvik.`,
 *   `libcore.`, `sun.`), falling back to the first frame if every frame is from a framework package.
 * - Keeps `"Class.method"` without the trailing `"(File.kt:123)"` so the same crash at a moved line
 *   still groups together.
 */
internal fun crashFingerprint(crash: InspectorCrashUi): String {
    val lines =
        crash.stackTrace
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    val exceptionClass = extractExceptionClass(lines, crash.summary)
    val frameMethods =
        lines
            .filter { it.startsWith("at ") }
            .mapNotNull(::extractFrameClassMethod)
    val primaryFrame =
        frameMethods.firstOrNull { frame -> !isFrameworkFrame(frame) }
            ?: frameMethods.firstOrNull()
    return if (primaryFrame != null) "$exceptionClass|$primaryFrame" else exceptionClass
}

private fun extractExceptionClass(
    nonEmptyLines: List<String>,
    summary: String,
): String {
    val candidateFromStack =
        nonEmptyLines
            .firstOrNull { line -> !line.startsWith("at ") && !line.startsWith("\"") && !line.startsWith("...") }
            ?.substringBefore(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val summaryHead = summary.substringBefore(':').trim()
    return candidateFromStack
        ?: summaryHead.takeIf { it.isNotEmpty() }?.let(::exceptionClassFromSummary)
        ?: nonEmptyLines
            .firstOrNull()
            ?.substringBefore(':')
            ?.trim()
            .orEmpty()
}

/** A summary like "java.io.IOException boom" names its class first; otherwise keep the whole head. */
private fun exceptionClassFromSummary(summaryHead: String): String {
    val leadingToken = summaryHead.substringBefore(' ').trim()
    val looksLikeClass = '.' in leadingToken || leadingToken.endsWith("Exception") || leadingToken.endsWith("Error")
    return if (looksLikeClass) leadingToken else summaryHead
}

private fun extractFrameClassMethod(frameLine: String): String? {
    val afterAt = frameLine.removePrefix("at ").trim()
    if (afterAt.isEmpty()) return null
    val withoutSource = afterAt.substringBefore('(').trim()
    val withoutModule = withoutSource.substringAfterLast('/')
    return withoutModule.takeIf { it.isNotEmpty() }
}

private fun isFrameworkFrame(classMethod: String): Boolean =
    FRAMEWORK_PACKAGE_PREFIXES.any { prefix -> classMethod.startsWith(prefix) }

/** True when at least two crashes in [crashes] share the same [crashFingerprint]. */
internal fun hasDuplicateCrashFingerprints(crashes: List<InspectorCrashUi>): Boolean {
    if (crashes.size < 2) return false
    val seen = mutableSetOf<String>()
    return crashes.any { crash -> !seen.add(crashFingerprint(crash)) }
}

/**
 * Groups [crashes] by [crashFingerprint], picking the newest crash (`maxBy timestampEpochMs`) as
 * the group representative and ordering groups newest-first (`sortedByDescending { it.lastSeenEpochMs }`).
 */
internal fun groupCrashes(crashes: List<InspectorCrashUi>): List<InspectorCrashGroup> {
    if (crashes.isEmpty()) return emptyList()
    val sortedNewestFirst = crashes.sortedByDescending { it.timestampEpochMs }
    return sortedNewestFirst
        .groupBy(::crashFingerprint)
        .map { (fingerprint, members) ->
            val newest = members.first()
            InspectorCrashGroup(
                fingerprint = fingerprint,
                newest = newest,
                count = members.size,
                lastSeenEpochMs = newest.timestampEpochMs,
                crashes = members,
            )
        }.sortedByDescending { it.lastSeenEpochMs }
}
