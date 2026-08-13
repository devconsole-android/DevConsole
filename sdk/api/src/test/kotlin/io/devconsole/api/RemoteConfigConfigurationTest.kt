/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.api

import io.devconsole.remoteconfig.RemoteConfigFetchInfo
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.remoteconfig.RemoteConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigConfigurationTest {
    private fun provider(id: String): RemoteConfigProvider =
        object : RemoteConfigProvider {
            override val id: String = id

            override fun snapshot(): RemoteConfigSnapshot =
                RemoteConfigSnapshot(id, emptyList(), RemoteConfigFetchInfo.unknown())
        }

    @Test
    fun `defaults to no remote config providers`() {
        assertTrue(DevConsoleConfig().remoteConfigProviders.isEmpty())
    }

    @Test
    fun `carries providers supplied through the additive setter`() {
        val config = DevConsoleConfig().withRemoteConfigProviders(listOf(provider("firebase")))

        assertEquals(listOf("firebase"), config.remoteConfigProviders.map { it.id })
    }

    @Test
    fun `the java builder accumulates providers`() {
        val config =
            DevConsoleConfig
                .builder()
                .addRemoteConfigProvider(provider("firebase"))
                .addRemoteConfigProvider(provider("configcat"))
                .build()

        assertEquals(listOf("firebase", "configcat"), config.remoteConfigProviders.map { it.id })
    }

    /** The additive fields survive every other `with…` copy, or a config silently loses providers. */
    @Test
    fun `providers survive an unrelated policy copy`() {
        val config =
            DevConsoleConfig()
                .withRemoteConfigProviders(listOf(provider("firebase")))
                .withCaptureCategories(CaptureCategory.all())
                .withCrashPolicy(CrashPolicy())

        assertEquals(listOf("firebase"), config.remoteConfigProviders.map { it.id })
    }

    @Test
    fun `runtime equivalence distinguishes differing providers`() {
        val base = DevConsoleConfig().withRemoteConfigProviders(listOf(provider("firebase")))

        assertFalse(base.runtimeEquivalentTo(DevConsoleConfig()))
        assertTrue(base.runtimeEquivalentTo(base.withRemoteConfigProviders(base.remoteConfigProviders)))
    }
}
