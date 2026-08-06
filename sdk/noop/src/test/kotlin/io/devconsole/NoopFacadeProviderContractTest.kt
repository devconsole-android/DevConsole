/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole

import io.devconsole.api.DevConsoleFacadeProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Full/no-op API parity, part 1: [DevConsoleFacadeProvider] is the one shared contract both
 * `sdk:full` and `sdk:noop`'s internal `PlatformFacadeProvider` implement -- and the shared
 * [DevConsole] facade object (compiled from the identical `facade-shared` source file into both
 * modules) only ever calls through that interface or through overloads private to each module's
 * own `PlatformFacadeProvider`. The Kotlin compiler already refuses to build either module if its
 * `PlatformFacadeProvider` misses an interface member, so this is a belt-and-suspenders regression
 * sentinel (it survives a hypothetical future refactor to delegation/reflection that would no
 * longer be compiler-checked) rather than the primary parity defense -- see
 * [io.devconsole.OkHttpAdapterFullNoopParityTest] in this module for the parity risk that is *not*
 * compiler-enforced: the full/no-op OkHttp adapter pairs, which share no common interface.
 *
 * Mirrored by `FullFacadeProviderContractTest` in `sdk:full`; neither module depends on the
 * other, so the two sides cannot be compared directly in one test and are instead each checked
 * independently against their one shared anchor, [DevConsoleFacadeProvider].
 */
class NoopFacadeProviderContractTest {
    @Test
    fun `noop PlatformFacadeProvider implements every DevConsoleFacadeProvider member with a matching signature`() {
        val interfaceMethods = declaredInterfaceMethods(DevConsoleFacadeProvider::class.java)
        val implMethods = PlatformFacadeProvider::class.java.methods

        assertTrue(
            "expected DevConsoleFacadeProvider to declare members to check parity against",
            interfaceMethods.isNotEmpty(),
        )
        interfaceMethods.forEach { interfaceMethod ->
            val implemented =
                implMethods.any { impl ->
                    impl.name == interfaceMethod.name &&
                        impl.parameterTypes.contentEquals(interfaceMethod.parameterTypes)
                }
            assertTrue(
                "io.devconsole.noop.PlatformFacadeProvider is missing an implementation matching " +
                    "${interfaceMethod.signatureLabel()} -- a release build using sdk:noop would fail to " +
                    "link where a debug build using sdk:full succeeds",
                implemented,
            )
        }
    }

    private fun declaredInterfaceMethods(interfaceClass: Class<*>): List<Method> =
        interfaceClass.methods.filter { it.declaringClass == interfaceClass }

    private fun Method.signatureLabel(): String = "$name(${parameterTypes.joinToString(", ") { it.simpleName }})"
}
