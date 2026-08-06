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
 * Full/no-op API parity, part 1 (full side) -- see `NoopFacadeProviderContractTest` in `sdk:noop`
 * for the full rationale. This module cannot see `sdk:noop`'s `PlatformFacadeProvider` (neither
 * module depends on the other), so each side is independently checked against the one contract
 * they both implement, [DevConsoleFacadeProvider].
 */
class FullFacadeProviderContractTest {
    @Test
    fun `full PlatformFacadeProvider implements every DevConsoleFacadeProvider member with a matching signature`() {
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
                "io.devconsole.full.PlatformFacadeProvider is missing an implementation matching " +
                    "${interfaceMethod.signatureLabel()}",
                implemented,
            )
        }
    }

    private fun declaredInterfaceMethods(interfaceClass: Class<*>): List<Method> =
        interfaceClass.methods.filter { it.declaringClass == interfaceClass }

    private fun Method.signatureLabel(): String = "$name(${parameterTypes.joinToString(", ") { it.simpleName }})"
}
