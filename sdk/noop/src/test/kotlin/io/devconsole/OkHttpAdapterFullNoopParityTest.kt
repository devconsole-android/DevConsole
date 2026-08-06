/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Full/no-op API parity, part 2: every full/no-op capture-adapter pair, OkHttp's and Paho's alike
 * (the file name predates the Paho pair and is kept unchanged so existing references/CI selectors
 * still resolve). `sdk:full`'s `DevConsoleOkHttpInterceptor`, `DevConsoleMockInterceptor`,
 * `DevConsoleOkHttpWebSocketListener`, and `sdk:socket-paho`'s `DevConsolePahoMqtt` /
 * `DevConsolePahoMqttCallback` / `DevConsoleRecordingMqttPublisher` are the classes a host actually
 * constructs by hand (see `docs/WEBSOCKET_INSPECTOR.md`, `docs/NETWORK_ADAPTERS.md`,
 * `docs/MQTT_CAPTURE.md`), wrapped with `debugImplementation`/`releaseImplementation` pairs pointing
 * at `sdk:network-okhttp` / `sdk:network-okhttp-noop`, `sdk:socket-paho` / `sdk:socket-paho-noop`,
 * and friends (see `samples/foundation-app/build.gradle.kts`, `samples/compose-app/build.gradle.kts`). Unlike
 * `PlatformFacadeProvider` (see `NoopFacadeProviderContractTest`), these adapter pairs share no
 * common Kotlin interface -- they only coincide by package and class name -- so nothing but this
 * test stops one side's constructor from drifting out of sync with the other's. A host compiled
 * against the full-side shape in debug would then fail to *compile* (not just misbehave) once the
 * release variant substitutes the no-op module.
 *
 * There is no `api.api` dump to diff against for either side: both are Android library modules,
 * and the binary-compatibility-validator only emits dumps for Kotlin/JVM modules (see the root
 * `build.gradle.kts`'s `apiValidation` comment). Cross-module `java.lang.reflect` comparison is
 * also not viable here: `sdk:full` never depends on `sdk:noop` or its adapter modules (or vice
 * versa) -- by design, since a host chooses exactly one side per build variant -- so there is no
 * single classpath from which both a real and a no-op `DevConsoleOkHttpInterceptor` could be
 * loaded and compared without deliberately constructing two isolated `URLClassLoader`s pointed at
 * each module's own compiled output directory, which is brittle across AGP/Kotlin toolchain
 * versions. Comparing the checked-in `.kt` source text directly is the robust choice: no build
 * output paths to guess at, and it fails loudly (a missing colon, an empty parameter list) rather
 * than silently passing over an unparsable shape.
 *
 * Source-text parsing here deliberately goes beyond a single anchored regex, because a
 * column-zero-only `^(?:class|object)\s+` pattern silently drops every modified declaration
 * (`data class`, `sealed class`, `abstract class`, `enum class`, `open class`, `interface`,
 * `typealias`) and never looks inside a `companion object` -- which is exactly how
 * `DevConsoleRecordingWebSocket.wrap`, the documented entry point for wrapping a raw [okhttp3.WebSocket],
 * went uncompared. It also used to compare only class names and primary-constructor shapes, never
 * method names, parameter types, or return types -- exactly how `DevConsoleOkHttpWebSocketListener`
 * gaining a `recordOpen(WebSocket)` method with no no-op counterpart went undetected (it has since
 * been made `internal`, but nothing here would have caught it had it stayed public). This file now:
 * allows an arbitrary modifier prefix before `class`/`object`/`interface`/`typealias` (excluding the
 * declaration only when a `private`/`internal`/`protected` modifier is present, so visibility
 * filtering is unchanged); walks every function declaration in a module, public or not, so a
 * companion object's members are attributed to their enclosing type; and compares public function
 * *signatures* (name, parameter name/type pairs, and declared return type) in addition to class
 * presence and primary-constructor shape. A function's declared return type is compared only when
 * one is written in the source (`): Type =` / `): Type {`); an inferred (unwritten) return type
 * compares as a shared placeholder on both sides, since inferring it for real would require a
 * compiler, not text matching -- still enough to catch a changed or newly-added explicit type.
 */
class OkHttpAdapterFullNoopParityTest {
    private data class ModulePair(
        val label: String,
        val fullModule: String,
        val noopModule: String,
    )

    private val pairs =
        listOf(
            ModulePair("network-okhttp", "sdk/network-okhttp", "sdk/network-okhttp-noop"),
            ModulePair("mocks-okhttp", "sdk/mocks-okhttp", "sdk/mocks-okhttp-noop"),
            ModulePair("socket-okhttp", "sdk/socket-okhttp", "sdk/socket-okhttp-noop"),
            ModulePair("socket-paho", "sdk/socket-paho", "sdk/socket-paho-noop"),
        )

    /** A function's comparable shape: name, `(paramName, paramType)` pairs in order, and declared return type. */
    private data class FunctionSignature(
        val name: String,
        val params: List<Pair<String, String>>,
        val returnType: String,
    ) {
        override fun toString(): String {
            val paramText = params.joinToString(", ") { (paramName, type) -> "$paramName: $type" }
            return "$name($paramText): $returnType"
        }
    }

    private data class TypeDeclaration(
        val kind: String,
        val name: String,
        /** From this declaration's start offset to end-of-file; bracket-depth parsing is self-terminating. */
        val sourceToEndOfFile: String,
    )

    private class ModuleModel(
        val types: Map<String, TypeDeclaration>,
        val typeFunctions: Map<String, Set<FunctionSignature>>,
        val topLevelFunctions: Set<FunctionSignature>,
    )

    /**
     * Public classes this test found on the full side with no no-op counterpart. Empty now:
     * `DevConsoleRecordingWebSocket` (`sdk/socket-okhttp`) gained a no-op counterpart in
     * `sdk/socket-okhttp-noop` that delegates every call straight through and records nothing, so
     * the gap this allowlist used to document is closed. Left as an empty map (rather than removed
     * outright) so a future real gap has an obvious place to land, with the same loud-failure
     * behavior verified below.
     */
    private val knownFullOnlyClasses =
        emptyMap<String, Set<String>>()

    /**
     * Public function signatures this test found on the full side with no no-op counterpart,
     * keyed by `"<pair.label>:<owner>"` where `<owner>` is a type name or `(top-level)`. Empty now:
     * the one real gap this stronger comparison surfaced -- `DevConsoleOkHttpWebSocketListener`
     * (`sdk/socket-okhttp-noop`) declared no callback overrides at all, relying on
     * `WebSocketListener`'s inherited empty defaults instead of its own no-op overrides -- was
     * fixed by adding the matching no-op overrides rather than allowlisted, since a host could
     * still call any of them directly on the declared type. Left as an empty map so a future
     * *genuinely intentional* gap has an obvious, self-checked place to land (see the loop at the
     * end of the function-signature test below).
     */
    private val knownFullOnlyFunctions =
        emptyMap<String, Set<String>>()

    @Test
    fun `every public top-level class in a full OkHttp adapter module has a counterpart in its noop module`() {
        pairs.forEach { pair ->
            val fullModel = moduleModel(pair.fullModule)
            val noopModel = moduleModel(pair.noopModule)
            val expectedFullOnly = knownFullOnlyClasses[pair.label].orEmpty()

            val missingFromNoop = fullModel.types.keys - noopModel.types.keys - expectedFullOnly
            val unexpectedNoopOnly = noopModel.types.keys - fullModel.types.keys

            assertTrue(
                "${pair.label}: ${pair.noopModule} is missing public class(es) present in ${pair.fullModule}: " +
                    "$missingFromNoop -- a host that constructs one of these in a debug build would fail to " +
                    "compile once the release variant substitutes the no-op module",
                missingFromNoop.isEmpty(),
            )
            assertTrue(
                "${pair.label}: ${pair.noopModule} declares public class(es) with no counterpart in " +
                    "${pair.fullModule}: $unexpectedNoopOnly -- a no-op-only public type a host could come to " +
                    "depend on without ever seeing it in debug",
                unexpectedNoopOnly.isEmpty(),
            )
            // Keeps the allowlist itself honest: fail loudly if a "known gap" entry is renamed away,
            // removed, or (having been fixed) now has a no-op counterpart, rather than silently
            // exempting a class that no longer needs the exemption.
            expectedFullOnly.forEach { name ->
                assertTrue(
                    "$name is no longer full-only for ${pair.label} -- update knownFullOnlyClasses",
                    name in fullModel.types.keys,
                )
                assertTrue(
                    "$name unexpectedly now has a no-op counterpart for ${pair.label} -- remove it from " +
                        "knownFullOnlyClasses, the gap this test documents has been closed",
                    name !in noopModel.types.keys,
                )
            }
        }
    }

    @Test
    fun `common OkHttp adapter classes declare the same primary constructor parameter names and types on both sides`() {
        pairs.forEach { pair ->
            val fullModel = moduleModel(pair.fullModule)
            val noopModel = moduleModel(pair.noopModule)
            val shared = fullModel.types.keys intersect noopModel.types.keys
            assertTrue(
                "${pair.label}: expected at least one class shared by both sides to compare",
                shared.isNotEmpty(),
            )

            // Objects, interfaces, and typealiases have no primary constructor to compare.
            shared.filter { fullModel.types.getValue(it).kind == "class" }.forEach { className ->
                val fullParams = primaryConstructorParams(fullModel.types.getValue(className).sourceToEndOfFile)
                val noopParams = primaryConstructorParams(noopModel.types.getValue(className).sourceToEndOfFile)
                assertEquals(
                    "${pair.label}.$className: primary constructor shape differs between the full and no-op " +
                        "modules -- code compiled against one would not compile against the other",
                    fullParams,
                    noopParams,
                )
            }
        }
    }

    @Test
    fun `common OkHttp adapter classes declare the same public function signatures, companion objects included`() {
        pairs.forEach { pair ->
            val fullModel = moduleModel(pair.fullModule)
            val noopModel = moduleModel(pair.noopModule)
            val shared = fullModel.types.keys intersect noopModel.types.keys

            shared.forEach { typeName ->
                val fullFunctions = fullModel.typeFunctions[typeName].orEmpty()
                val noopFunctions = noopModel.typeFunctions[typeName].orEmpty()
                val allowlistKey = "${pair.label}:$typeName"
                val expectedFullOnly = knownFullOnlyFunctions[allowlistKey].orEmpty()

                val missingFromNoop =
                    fullFunctions.filterNot { it.toString() in expectedFullOnly }.toSet() - noopFunctions
                val unexpectedNoopOnly = noopFunctions - fullFunctions

                assertTrue(
                    "${pair.label}.$typeName: ${pair.noopModule} is missing public function(s) present in " +
                        "${pair.fullModule}: $missingFromNoop -- a host calling one of these on the declared " +
                        "type would fail to compile once the release variant substitutes the no-op module",
                    missingFromNoop.isEmpty(),
                )
                assertTrue(
                    "${pair.label}.$typeName: ${pair.noopModule} declares public function(s) with no " +
                        "counterpart in ${pair.fullModule}: $unexpectedNoopOnly -- a no-op-only public member " +
                        "a host could come to depend on without ever seeing it in debug",
                    unexpectedNoopOnly.isEmpty(),
                )
                expectedFullOnly.forEach { signatureText ->
                    assertTrue(
                        "$allowlistKey: '$signatureText' is no longer full-only -- update knownFullOnlyFunctions",
                        fullFunctions.any { it.toString() == signatureText },
                    )
                    assertTrue(
                        "$allowlistKey: '$signatureText' unexpectedly now has a no-op counterpart -- remove it " +
                            "from knownFullOnlyFunctions, the gap this test documents has been closed",
                        noopFunctions.none { it.toString() == signatureText },
                    )
                }
            }
        }
    }

    @Test
    fun `top-level OkHttp adapter functions declare the same public signatures on both sides`() {
        pairs.forEach { pair ->
            val fullModel = moduleModel(pair.fullModule)
            val noopModel = moduleModel(pair.noopModule)
            val allowlistKey = "${pair.label}:(top-level)"
            val expectedFullOnly = knownFullOnlyFunctions[allowlistKey].orEmpty()

            val missingFromNoop =
                fullModel.topLevelFunctions.filterNot { it.toString() in expectedFullOnly }.toSet() -
                    noopModel.topLevelFunctions
            val unexpectedNoopOnly = noopModel.topLevelFunctions - fullModel.topLevelFunctions

            assertTrue(
                "${pair.label}: ${pair.noopModule} is missing top-level public function(s) present in " +
                    "${pair.fullModule}: $missingFromNoop",
                missingFromNoop.isEmpty(),
            )
            assertTrue(
                "${pair.label}: ${pair.noopModule} declares top-level public function(s) with no counterpart " +
                    "in ${pair.fullModule}: $unexpectedNoopOnly",
                unexpectedNoopOnly.isEmpty(),
            )
        }
    }

    private fun moduleSourceDir(relativeModulePath: String): File =
        File(repoRoot(), "$relativeModulePath/src/main/kotlin")

    /**
     * Walks up from the working directory rather than assuming it, since Gradle's test working
     * directory varies by AGP version.
     */
    private fun repoRoot(): File {
        var candidate = File(".").absoluteFile
        while (!File(candidate, "settings.gradle.kts").exists()) {
            candidate =
                candidate.parentFile
                    ?: error("Could not locate settings.gradle.kts above ${File(".").absoluteFile}")
        }
        return candidate
    }

    /**
     * Where a top-level declaration's own text ends, for membership purposes (deciding which nested
     * `fun` lines belong to it). Naively using "the next column-zero line" breaks whenever a
     * multi-line parameter or return-type list closes back at column zero -- this codebase's ktlint
     * style does exactly that for a class whose primary constructor is declared directly (no
     * separate `constructor` line), e.g.:
     * ```
     * internal class CallPhaseTimestamps(
     *     private val nanoTime: () -> Long = System::nanoTime,
     * ) {
     * ```
     * `) {` sits at column zero but is a *continuation* of `CallPhaseTimestamps`'s own header, not a
     * fresh declaration; naively treating it as the boundary would truncate the class's membership
     * range before its body even starts, and every member inside would misclassify as a top-level
     * declaration instead. A boundary line is skipped as a continuation when either (a) parenthesis
     * depth tracked from [declLineIndex] is still open at that line, or (b) the line's first
     * character is `)`, `]`, `}`, or `:` -- all of which only ever appear at column zero here as the
     * tail of a wrapped signature, never as the start of a new declaration.
     */
    private fun nextRealDeclarationBoundaryOffset(
        file: ParsedFile,
        boundaryLineIndices: List<Int>,
        declLineIndex: Int,
    ): Int {
        var depth = 0
        var cursor = file.lineStarts[declLineIndex]
        boundaryLineIndices.filter { it > declLineIndex }.forEach { candidateLineIndex ->
            val candidateOffset = file.lineStarts[candidateLineIndex]
            while (cursor < candidateOffset) {
                when (file.text[cursor]) {
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                }
                cursor++
            }
            val firstChar = file.lines[candidateLineIndex][0]
            val isContinuation = depth != 0 || firstChar in CONTINUATION_LEAD_CHARS
            if (!isContinuation) {
                return candidateOffset
            }
        }
        return file.text.length
    }

    private companion object {
        /**
         * Top-level (column-zero) declaration start: an arbitrary run of modifier keywords
         * (`data`, `sealed`, `abstract`, `enum`, `open`, `fun` (for `fun interface`), `internal`,
         * `private`, ...) followed by `class`/`object`/`interface`/`typealias` and a name.
         * Visibility is decided separately from the captured modifier group so `internal`/`private`
         * declarations are excluded from the *public* comparison while still being tracked (see
         * [ModuleModel]) so their members are never mistaken for top-level functions.
         */
        val DECLARATION_START = Regex("""^((?:\w+\s+)*)(class|object|interface|typealias)\s+(\w+)""")

        /**
         * A function declaration's start: an arbitrary run of modifier keywords (`override`,
         * `internal`, `private`, `suspend`, ...) followed by `fun`, an optional extension receiver
         * (`OkHttpClient.Builder.installDevConsole`), and the opening `(` of its parameter list.
         * Matched against a *trimmed* line, so indentation never affects it.
         */
        val FUNCTION_START = Regex("""^((?:\w+\s+)*)fun\s+([A-Za-z_][\w.]*)\s*\(""")

        val NON_PUBLIC_MODIFIERS = setOf("private", "internal", "protected")

        /** Leading characters marking a column-zero line as a wrapped-signature continuation, not a new declaration. */
        val CONTINUATION_LEAD_CHARS = setOf(')', ']', '}', ':')
    }

    private data class RawDeclaration(
        val lineIndex: Int,
        val kind: String,
        val name: String,
        val isPublic: Boolean,
        val startOffset: Int,
        val endOffset: Int,
    )

    /**
     * Parses every `.kt` file under [relativeModulePath]'s `src/main/kotlin` into a [ModuleModel]:
     * every public top-level type (with a self-terminating, bracket-depth-safe source slice for
     * constructor parsing), every public function attributed to its enclosing type (including one
     * nested inside a `companion object`, since that is not itself a column-zero declaration and so
     * is simply inside its enclosing type's line range), and every genuinely top-level public
     * function (an extension function or free function, attributed to no type at all).
     *
     * A function that is itself a member of a non-public type (for example `internal fun
     * timingsFor(...)` inside `internal class CallPhaseTimestamps`) is deliberately excluded from
     * *both* [ModuleModel.typeFunctions] and [ModuleModel.topLevelFunctions] -- it is not part of
     * any public shape this test compares.
     */
    private fun moduleModel(relativeModulePath: String): ModuleModel {
        val types = mutableMapOf<String, TypeDeclaration>()
        val typeFunctions = mutableMapOf<String, MutableSet<FunctionSignature>>()
        val topLevelFunctions = mutableSetOf<FunctionSignature>()

        moduleSourceDir(relativeModulePath)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file -> indexFile(file.readText(), types, typeFunctions, topLevelFunctions) }

        return ModuleModel(types, typeFunctions, topLevelFunctions)
    }

    /** A file's text plus precomputed per-line absolute offsets, threaded through the indexing helpers below. */
    private class ParsedFile(
        val text: String,
        val lines: List<String>,
    ) {
        val lineStarts: IntArray =
            IntArray(lines.size).also { starts ->
                var offset = 0
                for (i in lines.indices) {
                    starts[i] = offset
                    offset += lines[i].length + 1
                }
            }
    }

    /** Indexes one file's text into the accumulating [types]/[typeFunctions]/[topLevelFunctions] maps. */
    private fun indexFile(
        text: String,
        types: MutableMap<String, TypeDeclaration>,
        typeFunctions: MutableMap<String, MutableSet<FunctionSignature>>,
        topLevelFunctions: MutableSet<FunctionSignature>,
    ) {
        val file = ParsedFile(text, text.split("\n"))
        // Column-zero, non-blank lines: the boundaries between top-level declarations in this
        // codebase's ktlint-formatted style (see the class kdoc above).
        val boundaryLineIndices =
            file.lines.indices.filter { i ->
                val line = file.lines[i]
                line.isNotEmpty() && line[0] != ' ' && line[0] != '\t'
            }
        val rawDeclarations = rawDeclarations(file, boundaryLineIndices)

        rawDeclarations.filter { it.isPublic }.forEach { declaration ->
            types[declaration.name] =
                TypeDeclaration(
                    kind = declaration.kind,
                    name = declaration.name,
                    sourceToEndOfFile = text.substring(declaration.startOffset),
                )
            typeFunctions.getOrPut(declaration.name) { mutableSetOf() }
        }

        indexFunctions(file, rawDeclarations, typeFunctions, topLevelFunctions)
    }

    private fun rawDeclarations(
        file: ParsedFile,
        boundaryLineIndices: List<Int>,
    ): List<RawDeclaration> =
        boundaryLineIndices.mapNotNull { lineIndex ->
            val match = DECLARATION_START.find(file.lines[lineIndex]) ?: return@mapNotNull null
            val modifiers = modifierWords(match.groupValues[1])
            val startOffset = file.lineStarts[lineIndex]
            val endOffset = nextRealDeclarationBoundaryOffset(file, boundaryLineIndices, lineIndex)
            RawDeclaration(
                lineIndex = lineIndex,
                kind = match.groupValues[2],
                name = match.groupValues[3],
                isPublic = modifiers.none { it in NON_PUBLIC_MODIFIERS },
                startOffset = startOffset,
                endOffset = endOffset,
            )
        }

    /** Scans every line for a public function declaration and attributes it to its enclosing type, or to none. */
    private fun indexFunctions(
        file: ParsedFile,
        rawDeclarations: List<RawDeclaration>,
        typeFunctions: MutableMap<String, MutableSet<FunctionSignature>>,
        topLevelFunctions: MutableSet<FunctionSignature>,
    ) {
        file.lines.forEachIndexed { lineIndex, rawLine ->
            val leading = rawLine.takeWhile { it == ' ' || it == '\t' }.length
            val trimmed = rawLine.substring(leading)
            val match = FUNCTION_START.find(trimmed) ?: return@forEachIndexed
            val modifiers = modifierWords(match.groupValues[1])
            if (modifiers.any { it in NON_PUBLIC_MODIFIERS }) return@forEachIndexed

            // match.range.last is the index of the matched '(' within `trimmed`.
            val openParenIndex = file.lineStarts[lineIndex] + leading + match.range.last
            val signature = functionSignatureAt(file.text, openParenIndex, match.groupValues[2])
            val lineOffset = file.lineStarts[lineIndex]
            val owner = rawDeclarations.firstOrNull { lineOffset in it.startOffset until it.endOffset }

            when {
                owner == null -> topLevelFunctions += signature
                owner.isPublic -> typeFunctions.getOrPut(owner.name) { mutableSetOf() }.add(signature)
                else -> Unit // member of a non-public type: not part of any public shape
            }
        }
    }

    private fun modifierWords(rawModifiers: String): List<String> =
        rawModifiers.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /**
     * Extracts a function's signature given the absolute index of its parameter list's opening
     * `(` within the *whole file's* text. Bracket-depth matching is self-terminating -- it stops
     * exactly when the parameter list's own parens balance back out -- so this is safe to run
     * against the full remaining file text without first having to compute a bounded slice for the
     * enclosing type (which matters for a top-level function like `installDevConsole`, whose
     * closing `)` this codebase's ktlint style places back at column zero -- indistinguishable from
     * a fresh top-level declaration by a column-zero-boundary heuristic alone).
     */
    private fun functionSignatureAt(
        fullText: String,
        openParenIndex: Int,
        name: String,
    ): FunctionSignature {
        val closeIndex = matchingCloseIndex(fullText, openParenIndex)
        val paramList = fullText.substring(openParenIndex + 1, closeIndex)
        val params = splitTopLevel(paramList, ',').map(String::trim).filter(String::isNotEmpty).map(::parseParam)
        return FunctionSignature(name, params, functionReturnType(fullText, closeIndex))
    }

    /**
     * The declared return type immediately after a function's parameter list, or [INFERRED_RETURN_TYPE]
     * when none is written (`fun foo() { ... }` / `fun foo() = ...` with no `: Type`). Scans only up
     * to the first `{` or `=` after [closeParenIndex], which is always this function's own body
     * opener or assignment -- never another declaration's, since the two are always textually
     * adjacent in valid Kotlin.
     */
    private fun functionReturnType(
        fullText: String,
        closeParenIndex: Int,
    ): String {
        var index = closeParenIndex + 1
        while (index < fullText.length && fullText[index] != '{' && fullText[index] != '=') {
            index++
        }
        val header = fullText.substring(closeParenIndex + 1, index).trim()
        return if (header.startsWith(":")) {
            header.removePrefix(":").trim().replace(Regex("""\s+"""), " ")
        } else {
            INFERRED_RETURN_TYPE
        }
    }

    private fun primaryConstructorParams(declarationSource: String): List<Pair<String, String>> {
        val openIndex = declarationSource.indexOf('(')
        require(openIndex >= 0) { "No primary constructor found in: ${declarationSource.lineSequence().first()}" }
        val closeIndex = matchingCloseIndex(declarationSource, openIndex)
        val parameterList = declarationSource.substring(openIndex + 1, closeIndex)
        return splitTopLevel(parameterList, ',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::parseParam)
    }

    /** Depth-aware, so a lambda-typed default value's own commas/colons/braces never end the match early. */
    private fun matchingCloseIndex(
        text: String,
        openIndex: Int,
    ): Int {
        var depth = 0
        var closeIndex = -1
        for (index in openIndex until text.length) {
            when (text[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        closeIndex = index
                    }
                }
            }
            if (closeIndex >= 0) break
        }
        require(closeIndex > openIndex) {
            "Unbalanced brackets starting at index $openIndex in: ${text.substring(openIndex).lineSequence().first()}"
        }
        return closeIndex
    }

    private fun parseParam(rawParameter: String): Pair<String, String> {
        var text = rawParameter.replace(Regex("""@\w+(\([^)]*\))?"""), "").trim()
        text =
            text
                .removePrefix("private ")
                .removePrefix("val ")
                .removePrefix("var ")
                .trim()
        text = text.removePrefix("val ").removePrefix("var ").trim()
        val withoutDefault = splitTopLevel(text, '=').first().trim()
        val colonIndex = withoutDefault.indexOf(':')
        require(colonIndex > 0) { "Expected 'name: Type' in constructor parameter: $rawParameter" }
        val name = withoutDefault.substring(0, colonIndex).trim()
        val type =
            withoutDefault
                .substring(colonIndex + 1)
                .trim()
                .trimEnd(',')
                .replace(Regex("""\s+"""), " ")
        return name to type
    }

    /** Splits on [delimiter] only at bracket depth zero, so it never breaks inside a lambda body or generic type. */
    private fun splitTopLevel(
        text: String,
        delimiter: Char,
    ): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (character in text) {
            when (character) {
                '(', '{', '[' -> {
                    depth++
                    current.append(character)
                }
                ')', '}', ']' -> {
                    depth--
                    current.append(character)
                }
                delimiter ->
                    if (depth == 0) {
                        parts.add(current.toString())
                        current.clear()
                    } else {
                        current.append(character)
                    }
                else -> current.append(character)
            }
        }
        parts.add(current.toString())
        return parts
    }
}

private const val INFERRED_RETURN_TYPE = "<inferred>"
