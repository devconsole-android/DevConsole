/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodeAuthorityTest {
    @Test
    fun `issued code is eight characters from the unambiguous alphabet`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })

        val info = authority.issueCode()

        assertEquals(SessionCodeAuthority.SESSION_CODE_LENGTH, info.code.length)
        assertTrue(info.code.all { it in SessionCodeAuthority.CODE_ALPHABET })
        assertTrue(info.browserUrl.contains("#code=${info.code}"))
    }

    @Test
    fun `valid code exchanges directly for a session with no approval step`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val info = authority.issueCode()

        val result = authority.exchange(info.code, "Chrome", "127.0.0.1")

        assertTrue(result is SessionCodeExchangeResult.Approved)
        val session = (result as SessionCodeExchangeResult.Approved).session
        assertTrue(sessions.isAuthorized(session.token))
    }

    @Test
    fun `code is single-use, so a second exchange of the same code is invalid`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val info = authority.issueCode()

        val first = authority.exchange(info.code, "Chrome", "127.0.0.1")
        val second = authority.exchange(info.code, "Chrome again", "127.0.0.1")

        assertTrue(first is SessionCodeExchangeResult.Approved)
        assertEquals(SessionCodeExchangeResult.Invalid, second)
    }

    @Test
    fun `wrong code is rejected via constant-time comparison without consuming the live code`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val info = authority.issueCode()
        val wrongCode = if (info.code == "22222222") "33333333" else "22222222"

        val rejected = authority.exchange(wrongCode, "Chrome", "127.0.0.1")
        assertEquals(SessionCodeExchangeResult.Invalid, rejected)

        // The real code is still live and unconsumed.
        val approved = authority.exchange(info.code, "Chrome", "127.0.0.1")
        assertTrue(approved is SessionCodeExchangeResult.Approved)
    }

    @Test
    fun `expired code is rejected and never falls back`() {
        var now = 0L
        val sessions = SessionAuthority(nowEpochMs = { now })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { now }, codeTtlMs = 10L)
        val info = authority.issueCode()

        now = 11L

        assertEquals(SessionCodeExchangeResult.Expired, authority.exchange(info.code, "Chrome", "127.0.0.1"))
        // No automatic regeneration: the code stays gone until the host calls issueCode() again.
        assertNull(authority.currentInfo())
        assertNull(authority.remainingTtlMs())
        assertEquals(SessionCodeExchangeResult.Invalid, authority.exchange(info.code, "Chrome", "127.0.0.1"))
    }

    @Test
    fun `a code is valid one ms before its expiry and already expired exactly at it`() {
        // ActiveCode.isExpired() uses `now >= expiresAtEpochMs`, the inclusive counterpart to
        // SessionAuthority's exclusive `expiresAtEpochMs > now`. Exercising the exact instant
        // pins that boundary so it cannot silently drift to the exclusive form and grant one
        // extra millisecond of validity.
        var now = 0L
        val sessions = SessionAuthority(nowEpochMs = { now })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { now }, codeTtlMs = 10L)
        val info = authority.issueCode()

        now = 9L
        assertTrue(
            "one ms before expiry must still exchange",
            authority.exchange(info.code, "Chrome", "127.0.0.1") is SessionCodeExchangeResult.Approved,
        )
    }

    @Test
    fun `a code exchanged exactly at its expiry instant is rejected as expired`() {
        var now = 0L
        val sessions = SessionAuthority(nowEpochMs = { now })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { now }, codeTtlMs = 10L)
        val info = authority.issueCode()

        now = 10L
        assertEquals(SessionCodeExchangeResult.Expired, authority.exchange(info.code, "Chrome", "127.0.0.1"))
    }

    @Test
    fun `issuing a new code immediately invalidates the previous one even if unconsumed`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val first = authority.issueCode()

        val second = authority.issueCode()

        assertNotEquals(first.code, second.code)
        assertEquals(SessionCodeExchangeResult.Invalid, authority.exchange(first.code, "Chrome", "127.0.0.1"))
        assertTrue(authority.exchange(second.code, "Chrome", "127.0.0.1") is SessionCodeExchangeResult.Approved)
    }

    @Test
    fun `session-count limits are enforced and still consume the code`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        assertTrue(sessions.configurePolicy(SessionPolicy(maxAuthenticatedSessions = 1)))
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val first = authority.issueCode()
        assertTrue(authority.exchange(first.code, "Chrome", "127.0.0.1") is SessionCodeExchangeResult.Approved)

        val second = authority.issueCode()
        val rejected = authority.exchange(second.code, "Firefox", "127.0.0.1")

        assertEquals(SessionCodeExchangeResult.Rejected, rejected)
        // A capacity rejection still consumes the code -- a retry of the same code is now invalid.
        assertEquals(SessionCodeExchangeResult.Invalid, authority.exchange(second.code, "Firefox", "127.0.0.1"))
    }

    @Test
    fun `exchanging without ever issuing a code is invalid`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })

        assertEquals(SessionCodeExchangeResult.Invalid, authority.exchange("ANYCODE1", "Chrome", "127.0.0.1"))
    }

    @Test
    fun `sessions minted by exchange share the session store for refresh and revoke`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val info = authority.issueCode()
        val exchanged = authority.exchange(info.code, "Chrome", "127.0.0.1") as SessionCodeExchangeResult.Approved
        val session = exchanged.session

        val refreshed = sessions.refresh(session.token)
        assertTrue(refreshed != null)
        assertFalse(sessions.isAuthorized(session.token))
        assertTrue(sessions.isAuthorized(refreshed!!.token))

        sessions.revokeIfPresent(refreshed.id)
        assertFalse(sessions.isAuthorized(refreshed.token))
    }

    @Test
    fun `remainingTtlMs counts down and clears on expiry`() {
        var now = 0L
        val sessions = SessionAuthority(nowEpochMs = { now })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { now }, codeTtlMs = 100L)
        authority.issueCode()

        assertEquals(100L, authority.remainingTtlMs())
        now = 40L
        assertEquals(60L, authority.remainingTtlMs())
        now = 100L
        assertNull(authority.remainingTtlMs())
    }

    @Test
    fun `successful exchange mints a fresh code so the device surface never shows a dead one`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        val info = authority.issueCode()

        authority.exchange(info.code, "Chrome", "127.0.0.1")

        // The surface keeps showing a code (non-null) -- but a NEW, exchangeable one, not the
        // consumed one (a displayed-but-dead code is a front door that cannot open).
        val current = authority.currentInfo()
        assertTrue(current != null)
        assertTrue(info.code != current!!.code)
        val second = authority.exchange(current.code, "Firefox", "127.0.0.2")
        assertTrue(second is SessionCodeExchangeResult.Approved)
    }

    @Test
    fun `reset clears the live code`() {
        val sessions = SessionAuthority(nowEpochMs = { 100L })
        val authority = SessionCodeAuthority(sessions, nowEpochMs = { 100L })
        authority.issueCode()

        authority.reset()

        assertNull(authority.currentInfo())
    }
}
