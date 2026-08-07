/**
 * @author Shakib
 * @since 19/07/26
 */
package io.devconsole.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

enum class DevConsoleVariantPolicy { ENABLED, DISABLED, PROTECTED }

abstract class DevConsoleExtension {
    /**
     * Variants that are allowed to carry the real DevConsole runtime. Defaults to `["debug"]`, which
     * on a flavored project resolves via each variant's AGP *build type* -- e.g. `productionDebug` and
     * `partnerDebug` are both covered without listing them by exact name.
     *
     * A variant's *exact name* appearing in this set is an unconditional opt-in: it is ENABLED even if
     * [protectedVariantPatterns] also matches it. This is the explicit escape hatch for a host that
     * really does want one specific variant to carry the real runtime.
     *
     * A variant matched only via its *build type* (the zero-config `"debug"` case) is weaker: if
     * [protectedVariantPatterns] matches the variant's full name, that variant is always PROTECTED --
     * a pattern like `"(?i)partner.*"` can force-protect an entire flavor even though its build type is
     * `debug`. Any variant matched by neither falls through to [defaultPolicy] (PROTECTED by default).
     */
    abstract val enabledVariants: SetProperty<String>

    /**
     * Regexes ([Regex.matches], full-match) that force a variant to PROTECTED, overriding a build-type
     * match in [enabledVariants] but not an exact-name match there. Historically this was the *only*
     * way a variant became protected (with everything else silently DISABLED); since [defaultPolicy]
     * now defaults to PROTECTED, this knob is mainly useful for two cases: pinning specific variant
     * names as protected even when a host has lowered [defaultPolicy] to DISABLED or ENABLED for
     * everything else, and force-protecting a whole flavor (e.g. `"(?i)partner.*"`) that would
     * otherwise be swept in by [enabledVariants]' build-type fallback.
     */
    abstract val protectedVariantPatterns: ListProperty<String>
    abstract val protectedDependencyPaths: SetProperty<String>

    /**
     * Policy applied to a variant that is neither in [enabledVariants] nor matched by
     * [protectedVariantPatterns]. **Defaults to `PROTECTED`.**
     *
     * Earlier versions of this plugin defaulted this to `DISABLED`, so only variants matching
     * `protectedVariantPatterns` (by default `(?i).*release`) were checked at all -- a release-signed
     * `staging`/`beta`/`preprod` build type got zero verification. Protect-by-default closes that
     * gap: every variant is now PROTECTED unless a host explicitly opts it into [enabledVariants], or
     * explicitly lowers this property back to `DISABLED`/`ENABLED`. Hosts that want the old
     * release-only behavior can set `defaultPolicy.set(DevConsoleVariantPolicy.DISABLED)` alongside
     * `protectedVariantPatterns.set(listOf("(?i).*release"))`, as the bundled samples do.
     */
    abstract val defaultPolicy: Property<DevConsoleVariantPolicy>
    abstract val failBuildOnUnsafeVariant: Property<Boolean>

    /**
     * Adds the right DevConsole artifact per variant so hosts do not hand-write the debug/release
     * split -- writing `implementation` instead of `debugImplementation` is the mistake the variant
     * policy exists to catch, so not requiring it in the first place is better. Variants that
     * already declare a DevConsole dependency are left alone.
     */
    abstract val autoWireDependencies: Property<Boolean>

    /** Coordinate version used by [autoWireDependencies]. Defaults to the plugin's own version. */
    abstract val sdkVersion: Property<String>
}

@DisableCachingByDefault(because = "cheap to regenerate; its inputs are a tiny in-memory map, not worth caching")
abstract class DevConsoleVariantReportTask : DefaultTask() {
    @get:Input abstract val variantPolicies: MapProperty<String, String>
    @get:OutputFile abstract val jsonReport: Property<File>

    @TaskAction
    fun writeReport() {
        val json = variantPolicies.get().toSortedMap().entries.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") {
            "  \"${it.key}\": \"${it.value}\""
        }
        jsonReport.get().apply {
            parentFile.mkdirs()
            writeText(json)
        }
    }
}

@DisableCachingByDefault(because = "verification-only; produces no file outputs, just a pass/fail build assertion")
abstract class VerifyDevConsoleProtectedArtifactsTask : DefaultTask() {
    /** Violations found among the variant's *declared* dependencies. */
    @get:Input abstract val violations: ListProperty<String>

    /**
     * Component display names on each protected variant's fully *resolved* runtime classpath, keyed
     * by variant. This is what catches the full runtime arriving transitively through another
     * dependency -- the case a declared-dependency scan cannot see.
     */
    @get:Input abstract val resolvedRuntimeComponents: MapProperty<String, List<String>>

    @get:Input abstract val protectedProjectPaths: SetProperty<String>

    @get:Input abstract val failOnUnsafeVariant: Property<Boolean>

    @TaskAction
    fun verify() {
        val declared = violations.get()
        val badProjectDisplayNames = protectedProjectPaths.get().map { "project $it" }.toSet()
        val transitive =
            resolvedRuntimeComponents.get().flatMap { (variant, components) ->
                components
                    .filter { it in badProjectDisplayNames || it.matches(FULL_RUNTIME_COORDINATE) }
                    .map { "$variant -> $it (resolved)" }
            }
        val found = (declared + transitive).distinct()
        if (found.isEmpty()) return
        val message = "Protected variants contain the full DevConsole runtime: ${found.joinToString()}"
        if (failOnUnsafeVariant.get()) error(message) else logger.warn("DevConsole: $message")
    }

    private companion object {
        /** Enabled runtime/adapters that must never reach a protected variant. */
        val FULL_RUNTIME_COORDINATE =
            Regex(
                "^io\\.github\\.devconsole-android:" +
                    "(?:devconsole|devconsole-network-okhttp|devconsole-mocks-okhttp|" +
                    "devconsole-socket-okhttp|devconsole-socket-paho|devconsole-push-firebase):.+",
            )
    }
}

@DisableCachingByDefault(
    because = "its report embeds absolute file paths from packagedArtifacts, which are not reproducible " +
        "across machines/checkouts and would make a cached result misleading",
)
abstract class VerifyDevConsolePackagedArtifactTask : DefaultTask() {
    @get:Input abstract val variantName: Property<String>

    @get:Input abstract val failOnUnsafeVariant: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packagedArtifacts: ConfigurableFileCollection

    @get:OutputFile abstract val jsonReport: Property<File>

    @TaskAction
    fun verify() {
        val artifacts =
            packagedArtifacts.files
                .flatMap { artifact ->
                    if (artifact.isDirectory) {
                        artifact.walkTopDown().filter(File::isFile).toList()
                    } else {
                        listOf(artifact)
                    }
                }.distinctBy(File::getAbsolutePath)
                .sortedBy(File::getAbsolutePath)
        val inspected =
            artifacts.map { artifact ->
                InspectedArtifact(
                    path = artifact.absolutePath,
                    bytes = artifact.length(),
                    sha256 = artifact.sha256(),
                )
            }
        val violations = artifacts.flatMap(::scanArtifact).distinct().sorted()
        val report = jsonReport.get()
        report.parentFile.mkdirs()
        report.writeText(reportJson(inspected, violations))
        if (violations.isNotEmpty()) {
            val message =
                "Protected variant ${variantName.get()} contains forbidden DevConsole packaged content: " +
                    violations.joinToString()
            if (failOnUnsafeVariant.get()) error(message) else logger.warn("DevConsole: $message")
        }
    }

    private fun scanArtifact(artifact: File): List<String> =
        when (artifact.extension.lowercase()) {
            "apk", "aab", "aar", "jar", "zip" ->
                ZipFile(artifact).use { zip ->
                    zip.entries().asSequence().flatMap { entry ->
                        val pathMatches =
                            FORBIDDEN_ENTRY_PREFIXES
                                .filter { entry.name.startsWith(it) }
                                .map { signature -> "${artifact.name}!/${entry.name} [$signature]" }
                        if (entry.isDirectory) {
                            pathMatches.asSequence()
                        } else {
                            val contentMatches =
                                zip.getInputStream(entry).use { input ->
                                    scanStream(
                                        input = input,
                                        location = "${artifact.name}!/${entry.name}",
                                        nestedZip = entry.name.endsWith(".jar") || entry.name.endsWith(".zip"),
                                    )
                                }
                            (pathMatches + contentMatches).asSequence()
                        }
                    }.toList()
                }
            "dex", "xml", "class" -> artifact.inputStream().use { scanStream(it, artifact.name, nestedZip = false) }
            else -> emptyList()
        }

    private fun scanStream(
        input: InputStream,
        location: String,
        nestedZip: Boolean,
    ): List<String> {
        if (nestedZip) {
            return runCatching {
                ZipInputStream(input).use { nested ->
                    buildList {
                        while (true) {
                            val entry = nested.nextEntry ?: break
                            if (!entry.isDirectory) {
                                val nestedLocation = "$location!/${entry.name}"
                                FORBIDDEN_ENTRY_PREFIXES
                                    .filter { entry.name.startsWith(it) }
                                    .forEach { add("$nestedLocation [$it]") }
                                addAll(scanBounded(nested, MAX_NESTED_ENTRY_BYTES, nestedLocation))
                            }
                            nested.closeEntry()
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
        return scanBounded(input, MAX_SCANNED_ENTRY_BYTES, location)
    }

    private fun scanBounded(
        input: InputStream,
        limit: Int,
        location: String,
    ): List<String> {
        val bounded = input.readBytesBounded(limit)
        return scanBytes(bounded.bytes, location) +
            if (bounded.limitExceeded) listOf("$location [scan-limit-exceeded:$limit]") else emptyList()
    }

    private fun scanBytes(
        bytes: ByteArray,
        location: String,
    ): List<String> =
        FORBIDDEN_SIGNATURES
            .filter { signature -> bytes.contains(signature.encodeToByteArray()) }
            .map { signature -> "$location [$signature]" }

    private fun InputStream.readBytesBounded(limit: Int): BoundedBytes {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return BoundedBytes(output.toByteArray(), limitExceeded = total == limit && read() >= 0)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun File.sha256(): String =
        inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

    private fun reportJson(
        inspected: List<InspectedArtifact>,
        violations: List<String>,
    ): String =
        buildString {
            append("{\n  \"variant\":\"").append(variantName.get().jsonEscape()).append("\",\n")
            append("  \"artifacts\":[")
            inspected.forEachIndexed { index, artifact ->
                if (index > 0) append(',')
                append("\n    {\"path\":\"")
                    .append(artifact.path.jsonEscape())
                    .append("\",\"bytes\":")
                    .append(artifact.bytes)
                    .append(",\"sha256\":\"")
                    .append(artifact.sha256)
                    .append("\"}")
            }
            if (inspected.isNotEmpty()) append('\n').append("  ")
            append("],\n  \"violations\":[")
            violations.forEachIndexed { index, violation ->
                if (index > 0) append(',')
                append("\n    \"").append(violation.jsonEscape()).append('"')
            }
            if (violations.isNotEmpty()) append('\n').append("  ")
            append("]\n}\n")
        }

    private fun String.jsonEscape(): String =
        buildString(length + 8) {
            for (character in this@jsonEscape) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }

    private data class InspectedArtifact(
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    private data class BoundedBytes(
        val bytes: ByteArray,
        val limitExceeded: Boolean,
    )

    private companion object {
        const val MAX_SCANNED_ENTRY_BYTES = 64 * 1024 * 1024
        const val MAX_NESTED_ENTRY_BYTES = 16 * 1024 * 1024

        val FORBIDDEN_ENTRY_PREFIXES =
            listOf(
                "assets/devconsole-web/",
                "devconsole-web/",
            )

        val FORBIDDEN_SIGNATURES =
            listOf(
                "DEVCONSOLE_ENABLED_FULL_V1",
                "DEVCONSOLE_ENABLED_NETWORK_OKHTTP_V1",
                "DEVCONSOLE_ENABLED_MOCKS_OKHTTP_V1",
                "DEVCONSOLE_ENABLED_SOCKET_OKHTTP_V1",
                "DEVCONSOLE_ENABLED_PUSH_FIREBASE_V1",
                "Lio/devconsole/server/ktor/KtorLocalServerEngine;",
                "io.devconsole.DevConsoleInitializer",
                ".devconsoleinitializer",
            )
    }
}

private const val DEFAULT_SDK_VERSION = "0.2.0"
private const val DEVCONSOLE_GROUP = "io.github.devconsole-android"

/**
 * Core runtime coordinate names whose presence means a host already declared the DevConsole runtime,
 * so core auto-wiring must stand down. An add-on coordinate in the same group (e.g. `devconsole-ui-compose`,
 * `devconsole-ui-views`, `devconsole-network-ktor`) is *not* the core runtime and must not suppress it.
 */
private val CORE_RUNTIME_COORDINATE_NAMES = setOf("devconsole", "devconsole-noop")

class DevConsoleVariantPolicyPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val packagedVerificationTasks = mutableListOf<TaskProvider<VerifyDevConsolePackagedArtifactTask>>()
        // variant name -> AGP build type name (e.g. "productionDebug" -> "debug"). Populated by the
        // AndroidComponentsExtension#onVariants callbacks in registerPackagedArtifactScans below. The
        // build type is what lets a flavored variant like "productionDebug" resolve against
        // enabledVariants' default of ["debug"] without a host enumerating every flavor combination --
        // see variantPolicy().
        val actualVariantBuildTypes = linkedMapOf<String, String?>()
        val extension = extensions.create<DevConsoleExtension>("devConsole").apply {
            // Zero-config, safe-by-default: debug runs the real runtime; every other variant is
            // PROTECTED (defaultPolicy below) so it gets the noop artifact and is verified to exclude
            // the full runtime. A host overrides enabledVariants to allow more variants the real
            // runtime. protectedVariantPatterns still force-protects matching names even if a host
            // lowers defaultPolicy. Leaving enabledVariants empty made the plugin protect nothing and
            // silently wire the noop artifact into debug -- the opposite of what it advertises.
            enabledVariants.convention(setOf("debug"))
            protectedVariantPatterns.convention(listOf("(?i).*release"))
            protectedDependencyPaths.convention(
                setOf(
                    ":sdk:full",
                    ":sdk:network-okhttp",
                    ":sdk:mocks-okhttp",
                    ":sdk:socket-okhttp",
                    ":sdk:socket-paho",
                    ":sdk:push-firebase",
                ),
            )
            // PROTECTED by default: a release-signed staging/beta/preprod build type (anything not in
            // enabledVariants) must be verified too, not just names matching protectedVariantPatterns.
            // See the KDoc on DevConsoleExtension.defaultPolicy for the full rationale and how to
            // restore the old release-pattern-only behavior.
            defaultPolicy.convention(DevConsoleVariantPolicy.PROTECTED)
            failBuildOnUnsafeVariant.convention(true)
            autoWireDependencies.convention(true)
            sdkVersion.convention(DEFAULT_SDK_VERSION)
        }
        registerPackagedArtifactScans(extension, packagedVerificationTasks, actualVariantBuildTypes)

        afterEvaluate {
            // A com.android.dynamic-feature module has neither an ApplicationExtension nor a
            // LibraryExtension, so the findByType chain below used to `return@afterEvaluate` and leave
            // the module with zero protection and zero warning -- the full runtime could ride into a
            // release dynamic-feature split completely unchecked. This plugin does not model the
            // dynamic-feature packaging path, so fail loudly (mirroring the applied-before-AGP case
            // below) rather than pretend to protect it.
            extensions.findByType<DynamicFeatureExtension>()?.let {
                error(
                    "DevConsole: io.github.devconsole-android does not protect " +
                        "com.android.dynamic-feature modules (project '${project.path}'). The variant " +
                        "policy, transitive classpath walk and packaged-artifact scan only cover " +
                        "com.android.application and com.android.library modules, so a dynamic-feature " +
                        "split would carry the full DevConsole runtime with no verification at all. " +
                        "Keep the DevConsole runtime out of dynamic-feature modules and apply " +
                        "io.github.devconsole-android on the base com.android.application module (and " +
                        "any com.android.library modules) instead.",
                )
            }
            extensions.findByType<ApplicationExtension>()
                ?: extensions.findByType<LibraryExtension>()
                ?: return@afterEvaluate
            // actualVariantBuildTypes is populated by the AndroidComponentsExtension#onVariants
            // callbacks registered in registerPackagedArtifactScans above. Those callbacks fire during
            // AGP's own variant computation, which AGP schedules via its own afterEvaluate/finalizeDsl
            // hooks -- if io.github.devconsole-android is applied *before* com.android.application
            // (or com.android.library) in the `plugins {}` block, this plugin's afterEvaluate is queued
            // first and runs before AGP's variant computation has happened, so actualVariantBuildTypes
            // is still empty here even though onVariants will (uselessly) fire moments later. The old
            // code silently fell back to build-type names in that case, which for a flavored project
            // does not match any real variant's runtime-classpath configuration -- the transitive
            // classpath walk and the packaged APK/AAB scan then vanish with no error at all. Fail
            // loudly instead: this is a plugin-ordering mistake, not a valid configuration.
            check(actualVariantBuildTypes.isNotEmpty()) {
                "DevConsole: io.github.devconsole-android observed no Android build variants " +
                    "for project '${project.path}', even though it has an Android extension " +
                    "configured. This almost always means io.github.devconsole-android was " +
                    "applied before com.android.application (or com.android.library) in the " +
                    "`plugins {}` block. Apply com.android.application (or com.android.library) " +
                    "before io.github.devconsole-android so its variant callbacks register in " +
                    "time -- otherwise the transitive classpath and packaged-artifact checks silently " +
                    "do not run."
            }
            val policies = actualVariantBuildTypes.mapValues { (variantName, buildType) ->
                variantPolicy(variantName, buildType, extension)
            }
            val report = tasks.register<DevConsoleVariantReportTask>("devConsoleVariantReport") {
                group = "verification"
                variantPolicies.putAll(policies.mapValues { it.value.name })
                jsonReport.set(layout.buildDirectory.file("reports/devconsole/variants.json").map { it.asFile })
            }
            if (extension.autoWireDependencies.get()) autoWireDependencies(policies, extension)
            registerProtectedVerifier(policies, report, extension, packagedVerificationTasks)
        }
    }

    private fun Project.registerPackagedArtifactScans(
        extension: DevConsoleExtension,
        scans: MutableList<TaskProvider<VerifyDevConsolePackagedArtifactTask>>,
        actualVariantBuildTypes: MutableMap<String, String?>,
    ) {
        plugins.withId("com.android.application") {
            extensions
                .getByType<ApplicationAndroidComponentsExtension>()
                .onVariants { variant ->
                    actualVariantBuildTypes[variant.name] = variant.buildType
                    if (variantPolicy(variant.name, variant.buildType, extension) != DevConsoleVariantPolicy.PROTECTED) {
                        return@onVariants
                    }
                    scans +=
                        tasks.register<VerifyDevConsolePackagedArtifactTask>(
                            "verify${variant.name.capitalized()}DevConsolePackagedArtifact",
                        ) {
                            group = "verification"
                            variantName.set(variant.name)
                            failOnUnsafeVariant.set(extension.failBuildOnUnsafeVariant)
                            packagedArtifacts.from(variant.artifacts.get(SingleArtifact.APK))
                            packagedArtifacts.from(variant.artifacts.get(SingleArtifact.BUNDLE))
                            packagedArtifacts.from(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                            jsonReport.set(
                                layout.buildDirectory.file(
                                    "reports/devconsole/${variant.name}-artifacts.json",
                                ).map { it.asFile },
                            )
                        }
                }
        }
        plugins.withId("com.android.library") {
            extensions
                .getByType<LibraryAndroidComponentsExtension>()
                .onVariants { variant ->
                    actualVariantBuildTypes[variant.name] = variant.buildType
                    if (variantPolicy(variant.name, variant.buildType, extension) != DevConsoleVariantPolicy.PROTECTED) {
                        return@onVariants
                    }
                    scans +=
                        tasks.register<VerifyDevConsolePackagedArtifactTask>(
                            "verify${variant.name.capitalized()}DevConsolePackagedArtifact",
                        ) {
                            group = "verification"
                            variantName.set(variant.name)
                            failOnUnsafeVariant.set(extension.failBuildOnUnsafeVariant)
                            packagedArtifacts.from(variant.artifacts.get(SingleArtifact.AAR))
                            packagedArtifacts.from(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                            jsonReport.set(
                                layout.buildDirectory.file(
                                    "reports/devconsole/${variant.name}-artifacts.json",
                                ).map { it.asFile },
                            )
                        }
                }
        }
    }

    /**
     * Resolves a variant's policy. [buildType] is the AGP build-type name backing this variant (e.g.
     * "debug" for the flavored variant "productionDebug") -- read from AGP's own variant model rather
     * than string-matched off [variantName], since on a flavored project the variant name is a
     * flavor+build-type concatenation that will not literally equal a build-type-only entry like the
     * `enabledVariants` convention's `"debug"`.
     *
     * Precedence, checked in this order:
     *  1. [variantName] itself is in [DevConsoleExtension.enabledVariants] -> ENABLED. This is the
     *     explicit, by-full-name opt-in escape hatch and always wins, even over a matching protected
     *     pattern.
     *  2. Otherwise, if [DevConsoleExtension.protectedVariantPatterns] matches [variantName] ->
     *     PROTECTED. A host force-protecting a whole flavor (e.g. `"(?i)partner.*"`) must be able to
     *     override the build-type fallback below -- a `partnerDebug` variant should stay PROTECTED
     *     even though its build type is `debug`, unless the host opted it in by exact name in step 1.
     *  3. Otherwise, if [buildType] is in [DevConsoleExtension.enabledVariants] -> ENABLED. This is
     *     what makes the zero-config default (`enabledVariants` defaulting to `["debug"]`) cover every
     *     flavor of a debug build without the host enumerating each flavor combination.
     *  4. Otherwise, [DevConsoleExtension.defaultPolicy].
     */
    private fun variantPolicy(
        variantName: String,
        buildType: String?,
        extension: DevConsoleExtension,
    ): DevConsoleVariantPolicy {
        val enabled = extension.enabledVariants.get()
        return when {
            enabled.contains(variantName) -> DevConsoleVariantPolicy.ENABLED
            extension.protectedVariantPatterns.get().any { it.toRegex().matches(variantName) } ->
                DevConsoleVariantPolicy.PROTECTED
            buildType != null && enabled.contains(buildType) -> DevConsoleVariantPolicy.ENABLED
            else -> extension.defaultPolicy.get()
        }
    }

    private fun String.capitalized(): String = replaceFirstChar { character -> character.uppercase() }

    /**
     * A variant already naming a DevConsole dependency -- a project dependency in this repository,
     * or an explicit coordinate -- keeps what it declared. Auto-wiring only fills a gap.
     *
     * "Already declared" must be checked against the variant's resolvable `${variant}RuntimeClasspath`
     * configuration's [org.gradle.api.artifacts.Configuration.getAllDependencies], not
     * `${variant}Implementation`'s own dependencies. AGP's per-source-set bucket configurations
     * (`debugImplementation`, `productionImplementation`, `implementation`, `productionDebugImplementation`,
     * ...) are *siblings*, not a chain -- `productionDebugImplementation.extendsFrom` does **not**
     * include `debugImplementation`; only the resolvable `productionDebugRuntimeClasspath` extends
     * from all of them at once. A host's real DevConsole dependency conventionally lives on the
     * build-type-only bucket (`debugImplementation("...:devconsole")`), so checking only
     * `${variant}Implementation`'s own dependencies (or a naive `extendsFrom` walk starting from it)
     * never sees it, and auto-wire adds a second, redundant DevConsole artifact on top --
     * duplicate `io.devconsole.DevConsole` classes at dex time. `allDependencies` is Gradle's own
     * "dependencies of this configuration plus everything it extends from, transitively" accessor, so
     * pointing it at the classpath configuration (which *does* extend from every relevant bucket) gets
     * this right without reimplementing configuration-graph traversal.
     */
    private fun Project.autoWireDependencies(
        policies: Map<String, DevConsoleVariantPolicy>,
        extension: DevConsoleExtension,
    ) {
        val version = extension.sdkVersion.get()
        policies.forEach { (variant, policy) ->
            val configurationName = "${variant}Implementation"
            val configuration = configurations.findByName(configurationName) ?: return@forEach
            val effectiveDependencies =
                configurations.findByName("${variant}RuntimeClasspath")?.allDependencies
                    ?: configuration.allDependencies
            val alreadyDeclared =
                effectiveDependencies.any { it.isDevConsole(extension.protectedDependencyPaths.get()) }
            if (alreadyDeclared) return@forEach
            val artifact = if (policy == DevConsoleVariantPolicy.ENABLED) "devconsole" else "devconsole-noop"
            dependencies.add(configurationName, "$DEVCONSOLE_GROUP:$artifact:$version")
        }
    }

    private fun org.gradle.api.artifacts.Dependency.isDevConsole(protectedProjectPaths: Set<String>): Boolean =
        when (this) {
            is ProjectDependency -> path.startsWith(":sdk:") || path in protectedProjectPaths
            // Only the core runtime coordinates count as "already declared" for the purpose of skipping
            // core auto-wire. Declaring only an add-on (devconsole-ui-compose / devconsole-ui-views /
            // devconsole-network-ktor) in the same group must NOT suppress wiring the core runtime.
            else -> group == DEVCONSOLE_GROUP && name in CORE_RUNTIME_COORDINATE_NAMES
        }

    private fun Project.registerProtectedVerifier(
        policies: Map<String, DevConsoleVariantPolicy>,
        report: TaskProvider<DevConsoleVariantReportTask>,
        extension: DevConsoleExtension,
        packagedVerificationTasks: List<TaskProvider<VerifyDevConsolePackagedArtifactTask>>,
    ) {
        val protectedPaths = extension.protectedDependencyPaths.get()
        val protectedVariants = policies.filterValues { it == DevConsoleVariantPolicy.PROTECTED }.keys
        val violations = protectedVariants.flatMap { variant ->
            val variantConfig = configurations.findByName("${variant}Implementation")
            val implConfig = configurations.findByName("implementation")
            val allDeps = (variantConfig?.dependencies.orEmpty() + implConfig?.dependencies.orEmpty())
            allDeps.mapNotNull { dep ->
                when {
                    dep is ProjectDependency && dep.path in protectedPaths -> "$variant -> ${dep.path}"
                    dep is org.gradle.api.artifacts.ExternalModuleDependency && dep.group == DEVCONSOLE_GROUP && dep.name == "devconsole" -> "$variant -> ${dep.group}:${dep.name}"
                    else -> null
                }
            }
        }
        // Walk each protected variant's runtime dependency *graph* lazily (at task execution) so
        // transitive inclusion of the full runtime is caught too. The graph (resolutionResult) yields
        // component identities without resolving artifact files, which for an Android classpath is
        // ambiguous across artifact types.
        val resolvedComponents = protectedVariants.map { variant ->
            val classpath = configurations.findByName("${variant}RuntimeClasspath")
                ?: error(
                    "DevConsole: protected variant '$variant' has no '${variant}RuntimeClasspath' " +
                        "configuration, so its transitive dependency graph cannot be verified. This " +
                        "usually means io.github.devconsole-android was applied before " +
                        "com.android.application (or com.android.library) in the `plugins {}` block, " +
                        "or '$variant' is a build-type name that does not correspond to a real " +
                        "variant on a flavored project (e.g. 'release' instead of " +
                        "'productionRelease'). Apply com.android.application (or com.android.library) " +
                        "before io.github.devconsole-android, or ensure enabledVariants / " +
                        "protectedVariantPatterns reference actual variant names.",
                )
            variant to classpath.incoming.resolutionResult.rootComponent.map { root ->
                val seen = linkedSetOf<String>()
                val queue = ArrayDeque(root.dependencies)
                while (queue.isNotEmpty()) {
                    val dependency = queue.removeFirst() as? ResolvedDependencyResult ?: continue
                    val selected = dependency.selected
                    val identity =
                        when (val componentId = selected.id) {
                            is ProjectComponentIdentifier -> "project ${componentId.projectPath}"
                            else -> componentId.displayName
                        }
                    if (seen.add(identity)) queue.addAll(selected.dependencies)
                }
                seen.toList()
            }
        }
        val verifier = tasks.register<VerifyDevConsoleProtectedArtifactsTask>("verifyDevConsoleProtectedArtifacts") {
            group = "verification"
            dependsOn(report)
            dependsOn(packagedVerificationTasks)
            this.violations.set(violations)
            resolvedComponents.forEach { (variant, provider) -> resolvedRuntimeComponents.put(variant, provider) }
            protectedProjectPaths.set(protectedPaths)
            this.failOnUnsafeVariant.set(extension.failBuildOnUnsafeVariant)
        }
        tasks.findByName("check")?.let { checkTask ->
            checkTask.dependsOn(verifier)
        }
        // `check` alone is not enough: `./gradlew bundleRelease && upload` (or `assembleRelease`)
        // never runs `check` and previously got zero enforcement. Wire the verifier as a dependency of
        // every protected variant's assemble<Variant>/bundle<Variant> task too, so building the
        // protected artifact itself always runs the checks. tasks.matching { }.configureEach { } is
        // lazy and safe even when a task name does not exist for this project type (e.g.
        // bundle<Variant> on a library module, which has no bundle task) -- it never forces the task
        // to be created, it just configures it if and when it is.
        protectedVariants.forEach { variant ->
            val capitalized = variant.capitalized()
            tasks.matching { it.name == "assemble$capitalized" }.configureEach { it.dependsOn(verifier) }
            tasks.matching { it.name == "bundle$capitalized" }.configureEach { it.dependsOn(verifier) }
        }
    }
}
