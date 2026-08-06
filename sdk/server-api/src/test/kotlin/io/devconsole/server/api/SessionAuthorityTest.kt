package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Covers what remains of the old `PairingAuthorityTest` once pairing, roles, and elevation are
 * gone: session creation, TTL/refresh, revocation, principal listing, the concurrent-session cap,
 * and thread safety. [SessionCodeAuthorityTest] covers the exchange flow that mints these sessions.
 */
class SessionAuthorityTest {
    @Test
    fun `configured session capacity is enforced`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        assertTrue(authority.configurePolicy(SessionPolicy(maxAuthenticatedSessions = 1)))

        val first = authority.createSession("Chrome", "127.0.0.1")
        assertTrue(first is SessionCreateResult.Created)

        val second = authority.createSession("Firefox", "127.0.0.1")
        assertEquals(SessionCreateResult.SessionLimitReached, second)
    }

    @Test
    fun `configurePolicy refuses to change while sessions are active`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        authority.createSession("Chrome", "127.0.0.1")

        assertFalse(authority.configurePolicy(SessionPolicy(maxAuthenticatedSessions = 5)))
    }

    @Test
    fun `session is authorized until revoked`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        val session = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        assertTrue(authority.isAuthorized(session.token))
        authority.revokeIfPresent(session.id)
        assertFalse(authority.isAuthorized(session.token))
    }

    @Test
    fun `revokeIfPresent reports whether a session existed`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        val session = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        assertTrue(authority.revokeIfPresent(session.id))
        assertFalse(authority.revokeIfPresent(session.id))
    }

    @Test
    fun `refresh rotates credentials and exposes no raw tokens in principal listing`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        val original = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        val refreshed = authority.refresh(original.token)

        assertTrue(refreshed != null)
        assertFalse(authority.isAuthorized(original.token))
        assertTrue(authority.isAuthorized(refreshed!!.token))
        assertEquals("Chrome", authority.principals().single().browserLabel)
        assertFalse(
            authority
                .principals()
                .single()
                .toString()
                .contains(refreshed.token),
        )
    }

    @Test
    fun `refresh of an unknown or expired token returns null`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })

        assertEquals(null, authority.refresh("no-such-token"))
    }

    @Test
    fun `sessionForToken expires with the session TTL`() {
        var now = 0L
        val authority = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 10L)
        val session = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        assertTrue(authority.sessionForToken(session.token) != null)
        now = 11L
        assertEquals(null, authority.sessionForToken(session.token))
    }

    @Test
    fun `a session is valid one ms before its expiry and expired exactly at it`() {
        // expiresAtEpochMs is computed as createdAt + sessionTtlMs; the comparison is a strict
        // `>`, so the exact expiry instant itself must already read as expired, not the last
        // valid moment. Off-by-one here would either reject a legitimately still-live session or
        // extend its lifetime one tick past the configured TTL.
        var now = 0L
        val authority = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 10L)
        val session = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        now = 9L
        assertTrue("one ms before expiry must still be authorized", authority.isAuthorized(session.token))
        assertTrue(authority.sessionForToken(session.token) != null)

        now = 10L
        assertFalse("exactly at expiry must already be rejected", authority.isAuthorized(session.token))
        assertEquals(null, authority.sessionForToken(session.token))
    }

    @Test
    fun `refresh extends the session past its original expiry rather than only rotating tokens`() {
        var now = 0L
        val authority = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 10L)
        val original = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        now = 9L // one ms before the original session would have expired
        val refreshed = authority.refresh(original.token)
        assertTrue(refreshed != null)

        now = 15L // past the original expiry (10), but within a fresh 10ms window from the refresh (9+10=19)
        assertTrue(
            "refresh must reset the TTL from the moment it was called",
            authority.isAuthorized(refreshed!!.token),
        )
    }

    @Test
    fun `reset revokes all existing sessions`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        val session = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session
        assertTrue(authority.isAuthorized(session.token))

        authority.reset()

        assertFalse(authority.isAuthorized(session.token))
        assertTrue(authority.principals().isEmpty())
    }

    @Test
    fun `purgeExpired drops only sessions past their TTL`() {
        var now = 0L
        val authority = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 10L)
        val session = (authority.createSession("Chrome", "127.0.0.1") as SessionCreateResult.Created).session

        now = 11L
        authority.purgeExpired()

        assertTrue(authority.principals().isEmpty())
        assertEquals(null, authority.sessionForToken(session.token))
    }

    @Test
    fun `limits active browsers to the configured capacity`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })

        repeat(10) { index ->
            val result = authority.createSession("Browser $index", "127.0.0.1")
            assertTrue(result is SessionCreateResult.Created)
        }

        val overCapacity = authority.createSession("Browser 10", "127.0.0.1")
        assertEquals(SessionCreateResult.SessionLimitReached, overCapacity)
    }

    @Test
    fun `drives session creation concurrently up to capacity without exceeding it`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        assertTrue(authority.configurePolicy(SessionPolicy(maxAuthenticatedSessions = 5)))
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = mutableListOf<SessionCreateResult>()

        repeat(threadCount) { index ->
            executor.execute {
                readyLatch.countDown()
                startLatch.await()
                val result = authority.createSession("Browser $index", "127.0.0.1")
                synchronized(results) { results.add(result) }
                doneLatch.countDown()
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val createdCount = results.count { it is SessionCreateResult.Created }
        val rejectedCount = results.count { it == SessionCreateResult.SessionLimitReached }
        assertEquals(5, createdCount)
        assertEquals(threadCount - 5, rejectedCount)
    }

    @Test
    fun `drives reads concurrently with session creation without exception`() {
        val authority = SessionAuthority(nowEpochMs = { 100L })
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val doneLatch = CountDownLatch(threadCount)
        val errors = mutableListOf<Throwable>()

        repeat(threadCount) { index ->
            executor.execute {
                try {
                    repeat(100) { iter ->
                        if (iter % 2 == 0) {
                            authority.createSession("Browser $index-$iter", "127.0.0.1")
                        } else {
                            authority.isAuthorized("invalid-token")
                            authority.principals()
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("Expected no exceptions, but got: $errors", errors.isEmpty())
    }
}
