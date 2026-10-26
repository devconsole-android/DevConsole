package io.devconsole.server.ktor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerHostAllowlistTest {
    @Test
    fun `hostnameFrom reduces whatever people paste to a bare hostname`() {
        mapOf(
            "api.acme.com" to "api.acme.com",
            "  API.Acme.COM  " to "api.acme.com",
            "https://api.acme.com/api/v1/orders?x=1#top" to "api.acme.com",
            "http://user:secret@api.acme.com:8443/path" to "api.acme.com",
            "api.acme.com:8080" to "api.acme.com",
            "10.0.2.2" to "10.0.2.2",
            "localhost" to "localhost",
            "my_service.internal" to "my_service.internal",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, ComposerHostAllowlist.hostnameFrom(raw)) }
    }

    @Test
    fun `hostnameFrom rejects wildcards, empties and things that are not hostnames`() {
        listOf(
            "",
            "   ",
            "*.acme.com",
            "api..acme.com",
            ".acme.com",
            "acme.com.",
            "api acme.com",
            "[::1]",
            "-bad.example",
            "a".repeat(64) + ".example",
            "a.".repeat(130) + "com",
        ).forEach { raw -> assertNull(raw, ComposerHostAllowlist.hostnameFrom(raw)) }
    }

    @Test
    fun `configured hosts are always allowed and cannot be removed`() {
        val list = ComposerHostAllowlist(setOf("API.Example.test"), editable = true)

        assertTrue(list.permits("api.example.test"))
        assertEquals(ComposerHostAllowlist.RemoveResult.SetByApp, list.remove("api.example.test"))
        assertTrue(list.permits("api.example.test"))
    }

    @Test
    fun `session hosts can be added once, listed with their source, and removed`() {
        val list = ComposerHostAllowlist(setOf("a.example"), editable = true)

        assertEquals(ComposerHostAllowlist.AddResult.Added("api.acme.com"), list.add("https://api.acme.com/x"))
        assertEquals(ComposerHostAllowlist.AddResult.AlreadyAllowed("api.acme.com"), list.add("api.acme.com"))
        assertEquals(ComposerHostAllowlist.AddResult.AlreadyAllowed("a.example"), list.add("a.example"))
        assertTrue(list.permits("api.acme.com"))
        assertEquals(
            listOf(
                ComposerHostAllowlist.Entry("a.example", ComposerHostAllowlist.Source.APP),
                ComposerHostAllowlist.Entry("api.acme.com", ComposerHostAllowlist.Source.SESSION),
            ),
            list.entries(),
        )

        assertEquals(ComposerHostAllowlist.RemoveResult.Removed, list.remove("API.acme.com"))
        assertFalse(list.permits("api.acme.com"))
        assertEquals(ComposerHostAllowlist.RemoveResult.NotFound, list.remove("api.acme.com"))
    }

    @Test
    fun `invalid input is not added and the session list is bounded`() {
        val list = ComposerHostAllowlist(setOf("acme.com"), editable = true)

        assertEquals(ComposerHostAllowlist.AddResult.Invalid, list.add("*.acme.com"))
        repeat(ComposerHostAllowlist.MAX_SESSION_HOSTS) { list.add("host$it.example") }
        assertEquals(ComposerHostAllowlist.AddResult.LimitReached, list.add("one-too-many.example"))
        assertFalse(list.permits("one-too-many.example"))
    }

    @Test
    fun `an app that configures no hosts permits any host and session hosts never narrow it`() {
        val list = ComposerHostAllowlist(emptySet(), editable = true)

        assertTrue(list.permitsAnyHost)
        assertTrue(list.permits("api.acme.com"))
        assertEquals(ComposerHostAllowlist.AddResult.AlreadyAllowed("acme.com"), list.add("acme.com"))
        assertTrue(list.permits("other.example"))
        assertFalse(list.permits(""))
    }

    @Test
    fun `permits matches the host exactly, never a suffix`() {
        val list = ComposerHostAllowlist(setOf("acme.com"), editable = false)

        assertTrue(list.permits("ACME.com"))
        assertFalse(list.permits("api.acme.com"))
        assertFalse(list.permits("evilacme.com"))
        assertFalse(list.permits(""))
    }
}
