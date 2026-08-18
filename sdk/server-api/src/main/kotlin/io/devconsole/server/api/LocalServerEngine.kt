package io.devconsole.server.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class BindingMode { LOOPBACK, LAN }

/**
 * Session-count policy. Every authenticated session is equivalent (no role ladder -- see
 * [SessionAuthority]); [maxAuthenticatedSessions] is the only cap that still applies, mirroring
 * what used to be `PairingSessionPolicy.maxAuthenticatedSessions` before the CONTROL/ADMIN-specific
 * caps were removed along with the role ladder itself.
 */
data class SessionPolicy(
    val maxAuthenticatedSessions: Int = SessionAuthority.DEFAULT_MAX_AUTHENTICATED_SESSIONS,
) {
    init {
        require(maxAuthenticatedSessions > 0) { "maxAuthenticatedSessions must be positive" }
    }
}

data class StartRequest(
    val bindingMode: BindingMode = BindingMode.LAN,
    val portRange: IntRange = 8080..8099,
    val sessionCodeTtlMs: Long = SessionCodeAuthority.DEFAULT_SESSION_CODE_TTL_MS,
)

data class Endpoint(
    val host: String,
    val port: Int,
    val bindingMode: BindingMode,
)

/** Stable, non-sensitive metadata exposed to an authenticated dashboard. */
data class ServerMetadata(
    val protocolVersion: Int = 1,
    val appDisplayName: String = "Unknown app",
    val appPackageName: String = "unknown",
    val appVersionName: String = "unknown",
    val buildVariant: String = "unknown",
    val capabilities: List<String> = DEFAULT_CAPABILITIES,
    /**
     * Wire-name strings of the host's enabled [io.devconsole.api.CaptureCategory] set. Plain strings,
     * not the enum itself -- this module must not gain a dependency on `:sdk:api` just for this field.
     */
    val captureCategories: List<String> = DEFAULT_CAPTURE_CATEGORIES,
) {
    init {
        require(protocolVersion > 0) { "protocolVersion must be positive" }
    }

    companion object {
        val DEFAULT_CAPABILITIES: List<String> =
            listOf(
                "timeline",
                "network",
                "websockets",
                "composer",
                "mocks",
                "state",
                "push",
            )

        val DEFAULT_CAPTURE_CATEGORIES: List<String> =
            listOf(
                "network",
                "socket",
                "mqtt",
                "push",
                "logs",
                "crashes",
                "state",
                "inspection",
                "mocks",
            )
    }
}

/** Bounded, credential-free counters for the SDK Health dashboard. */
data class SdkHealthSnapshot(
    val initializationCount: Long = 0,
    val publishedEventCount: Long = 0,
    val droppedEventCount: Long = 0,
    val state: String = "UNINITIALIZED",
    val activePrincipalCount: Int = 0,
) {
    init {
        require(initializationCount >= 0) { "initializationCount must not be negative" }
        require(publishedEventCount >= 0) { "publishedEventCount must not be negative" }
        require(droppedEventCount >= 0) { "droppedEventCount must not be negative" }
        require(activePrincipalCount >= 0) { "activePrincipalCount must not be negative" }
    }
}

sealed interface ServerStartResult {
    data class Started(
        val endpoint: Endpoint,
        val sessionCode: SessionCodeInfo,
    ) : ServerStartResult

    data object DisabledForBuild : ServerStartResult

    data object LocalNetworkPermissionRequired : ServerStartResult

    data class InvalidConfiguration(
        val detail: String,
    ) : ServerStartResult

    data class PortUnavailable(
        val attempted: IntRange,
    ) : ServerStartResult

    data class NoEligibleNetwork(
        val detail: String,
    ) : ServerStartResult

    data class Failed(
        val detail: String,
    ) : ServerStartResult
}

interface LocalServerEngine {
    suspend fun start(request: StartRequest): ServerStartResult

    suspend fun stop()

    /**
     * True once the device's live LAN address no longer matches the one the server bound to at
     * start -- e.g. a DHCP lease change while the server keeps running. The server keeps
     * advertising the now-dead address either way; this is purely a detection signal for the UI
     * to surface honestly (see the More screen) rather than an automatic rebind. Always false for
     * LOOPBACK binding, while stopped, or for an engine that doesn't implement detection.
     */
    fun bindAddressChanged(): Boolean = false
}

/**
 * Session-code data; never persisted. Possessing [code] within [expiresAtEpochMs] *is* the entire
 * trust decision -- there is no on-device approval step. See [SessionCodeAuthority.exchange] and
 * docs/THREAT_MODEL.md.
 */
class SessionCodeInfo internal constructor(
    val browserUrl: String,
    val code: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String = "SessionCodeInfo(expiresAtEpochMs=$expiresAtEpochMs)"
}

class BrowserSession internal constructor(
    val id: String,
    val token: String,
    val csrfToken: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String = "BrowserSession(id=$id, expiresAtEpochMs=$expiresAtEpochMs)"
}

/** Safe browser metadata for the Session view; credentials never appear here. */
data class BrowserPrincipal(
    val id: String,
    val browserLabel: String,
    val sourceIp: String,
    val expiresAtEpochMs: Long,
)

/** Result of minting a session ([SessionAuthority.createSession]). */
sealed interface SessionCreateResult {
    data class Created(
        val session: BrowserSession,
    ) : SessionCreateResult

    /** [SessionPolicy.maxAuthenticatedSessions] is already at capacity. */
    data object SessionLimitReached : SessionCreateResult
}

/** Result of [SessionCodeAuthority.exchange]. */
sealed interface SessionCodeExchangeResult {
    data class Approved(
        val session: BrowserSession,
    ) : SessionCodeExchangeResult

    /** Rejected purely on session-count capacity; see [SessionPolicy.maxAuthenticatedSessions]. */
    data object Rejected : SessionCodeExchangeResult

    /** No code has ever been issued, the presented code does not match, or it was already consumed. */
    data object Invalid : SessionCodeExchangeResult

    data object Expired : SessionCodeExchangeResult
}

/**
 * In-memory authority for the browser session store: creation, TTL/refresh, CSRF tokens, listing,
 * and revocation. One access tier -- every session is equivalent; mutating routes gate on the
 * host's `EditingCapabilities` flags instead of a session-level role. [SessionCodeAuthority] is the
 * sole way a session gets minted, via [createSession].
 */
class SessionAuthority(
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val maxAuthenticatedSessions: Int = DEFAULT_MAX_AUTHENTICATED_SESSIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    private val sessions = mutableMapOf<String, BrowserSession>()
    private val sessionMetadata = mutableMapOf<String, SessionIdentity>()
    private var policy = SessionPolicy(maxAuthenticatedSessions = maxAuthenticatedSessions)

    init {
        require(sessionTtlMs > 0) { "sessionTtlMs must be positive" }
        require(maxAuthenticatedSessions > 0) { "maxAuthenticatedSessions must be positive" }
    }

    /**
     * Applies host configuration before any session exists. A live authority cannot silently
     * weaken or expand policy while identities exist.
     */
    @Synchronized
    fun configurePolicy(policy: SessionPolicy): Boolean {
        val allowed = this.policy == policy || sessions.isEmpty()
        if (allowed) this.policy = policy
        return allowed
    }

    @Synchronized
    fun reset() {
        sessions.clear()
        sessionMetadata.clear()
    }

    @Synchronized
    fun purgeExpired() {
        val now = nowEpochMs()
        val expiredSessions = sessions.filterValues { it.expiresAtEpochMs <= now }.keys.toList()
        for (id in expiredSessions) {
            sessions.remove(id)
            sessionMetadata.remove(id)
        }
    }

    /**
     * Creates a browser session immediately, without an on-device approval step, honoring
     * [SessionPolicy.maxAuthenticatedSessions]. Used by [SessionCodeAuthority] so a SESSION_CODE
     * exchange and every other session share one store, one CSRF/refresh/revoke implementation, and
     * one session-count limit.
     */
    @Synchronized
    internal fun createSession(
        browserLabel: String,
        sourceIp: String,
    ): SessionCreateResult {
        val activeSessions = sessions.values.count { it.expiresAtEpochMs > nowEpochMs() }
        if (activeSessions >= policy.maxAuthenticatedSessions) return SessionCreateResult.SessionLimitReached
        val session =
            BrowserSession(
                id = UUID.randomUUID().toString(),
                token = randomUrlToken(TOKEN_BYTE_LENGTH),
                csrfToken = randomUrlToken(TOKEN_BYTE_LENGTH),
                expiresAtEpochMs = nowEpochMs() + sessionTtlMs,
            )
        sessions[session.id] = session
        sessionMetadata[session.id] = SessionIdentity(browserLabel.take(MAX_BROWSER_LABEL_LENGTH), sourceIp)
        return SessionCreateResult.Created(session)
    }

    /** Revokes [sessionId] if it is currently live; returns false for an unknown/already-expired id. */
    @Synchronized
    fun revokeIfPresent(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        if (removed) sessionMetadata.remove(sessionId)
        return removed
    }

    /** Rotates both bearer and CSRF credentials while preserving the approved browser identity. */
    @Synchronized
    fun refresh(token: String): BrowserSession? {
        val current =
            sessions.values.firstOrNull {
                constantTimeEquals(token, it.token) && it.expiresAtEpochMs > nowEpochMs()
            } ?: return null
        val refreshed =
            BrowserSession(
                id = current.id,
                token = randomUrlToken(TOKEN_BYTE_LENGTH),
                csrfToken = randomUrlToken(TOKEN_BYTE_LENGTH),
                expiresAtEpochMs = nowEpochMs() + sessionTtlMs,
            )
        sessions[current.id] = refreshed
        return refreshed
    }

    @Synchronized
    fun principals(): List<BrowserPrincipal> =
        sessions.values
            .filter { it.expiresAtEpochMs > nowEpochMs() }
            .map { session ->
                val identity = sessionMetadata[session.id]
                BrowserPrincipal(
                    id = session.id,
                    browserLabel = identity?.browserLabel ?: "Unknown browser",
                    sourceIp = identity?.sourceIp ?: "unknown",
                    expiresAtEpochMs = session.expiresAtEpochMs,
                )
            }

    @Synchronized
    fun isAuthorized(token: String): Boolean =
        sessions.values.any { session ->
            constantTimeEquals(token, session.token) && session.expiresAtEpochMs > nowEpochMs()
        }

    @Synchronized
    fun sessionForToken(token: String): BrowserSession? =
        sessions.values.firstOrNull { session ->
            constantTimeEquals(token, session.token) && session.expiresAtEpochMs > nowEpochMs()
        }

    @OptIn(ExperimentalEncodingApi::class)
    private fun randomUrlToken(byteCount: Int): String =
        ByteArray(byteCount)
            .also(random::nextBytes)
            .let { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(it) }

    private data class SessionIdentity(
        val browserLabel: String,
        val sourceIp: String,
    )

    companion object {
        const val DEFAULT_SESSION_TTL_MS: Long = 30 * 60 * 1000L
        const val DEFAULT_MAX_AUTHENTICATED_SESSIONS: Int = 10
        private const val TOKEN_BYTE_LENGTH = 32
        private const val MAX_BROWSER_LABEL_LENGTH = 128
    }
}

/**
 * [String.equals] stops at the first differing byte, which over a LAN turns repeated guesses into a
 * byte-at-a-time oracle for a bearer token or session code. Compare the whole value instead. Shared
 * by [SessionAuthority] and [SessionCodeAuthority] so every credential comparison in this module goes
 * through one implementation.
 */
internal fun constantTimeEquals(
    presented: String,
    expected: String,
): Boolean = MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

/**
 * SESSION_CODE access flow: an 8-character, unambiguous-alphabet code with its own TTL, exchanged
 * directly for a browser session -- no on-device Approve/Deny round trip. Possession of the code
 * within its TTL *is* the authorization decision; mutating routes still separately require the
 * relevant `EditingCapabilities` flag on the host.
 *
 * **Single-use, by design.** The code is consumed by the first [exchange] attempt that matches it --
 * including one rejected for session capacity. That closes the window during which a code visible in
 * a screen share, a shoulder surf, or browser history could be replayed by a second party after the
 * intended browser already claimed it. A host that wants more than one browser connected issues a
 * fresh code per browser; concurrent sessions are still bounded by [SessionPolicy.maxAuthenticatedSessions]
 * either way, so single-use costs nothing there.
 *
 * Only one code is ever live: issuing a new one immediately invalidates the previous one, consumed or
 * not. There is no automatic regeneration and no fallback -- **no-fallback expiry** -- the host must
 * call [issueCode] again explicitly.
 *
 * Sessions minted by [exchange] are created through [SessionAuthority.createSession], so they live in
 * the exact same session store as every other session: refresh, logout, CSRF, and session-count
 * limits behave identically regardless of how the session was minted.
 */
class SessionCodeAuthority(
    private val sessionAuthority: SessionAuthority,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val codeTtlMs: Long = DEFAULT_SESSION_CODE_TTL_MS,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(codeTtlMs > 0) { "codeTtlMs must be positive" }
    }

    private var activeCode: ActiveCode? = null

    /**
     * The endpoint/TTL from the most recent [issueCode] call -- reused by a caller that re-issues
     * with no arguments (e.g. [currentInfo]'s auto re-mint) so a fresh code still points at the
     * real bind address instead of falling back to the initial placeholder endpoint.
     */
    private var lastEndpoint: Endpoint = Endpoint(DEFAULT_LOOPBACK_HOST, DEFAULT_LOOPBACK_PORT, BindingMode.LOOPBACK)
    private var lastTtlMs: Long = codeTtlMs

    /** Issues a new code, immediately invalidating any previous one (one live code at a time). */
    @Synchronized
    fun issueCode(
        endpoint: Endpoint = lastEndpoint,
        ttlMs: Long = lastTtlMs,
    ): SessionCodeInfo {
        require(ttlMs > 0) { "ttlMs must be positive" }
        lastEndpoint = endpoint
        lastTtlMs = ttlMs
        val expiresAt = nowEpochMs() + ttlMs
        val code = randomCode()
        val browserUrl = "http://${endpoint.host}:${endpoint.port}/#code=$code"
        activeCode = ActiveCode(code, browserUrl, expiresAt)
        return SessionCodeInfo(browserUrl, code, expiresAt)
    }

    /**
     * The live, unexpired code's display info. A successful exchange consumes the old code and
     * immediately mints a fresh one, so what this returns is always exchangeable -- the device
     * surface never advertises a dead URL. Null once the code has expired or none was ever issued.
     */
    @Synchronized
    fun currentInfo(): SessionCodeInfo? =
        activeCode?.takeUnless(ActiveCode::isExpired)?.let {
            SessionCodeInfo(it.browserUrl, it.code, it.expiresAtEpochMs)
        }

    /** Remaining TTL in ms for the live, unexpired code; null once it has expired or none was issued. */
    @Synchronized
    fun remainingTtlMs(): Long? =
        activeCode?.takeUnless(ActiveCode::isExpired)?.let { (it.expiresAtEpochMs - nowEpochMs()).coerceAtLeast(0) }

    /** Drops the live code without affecting sessions already minted from it. */
    @Synchronized
    fun clear() {
        activeCode = null
    }

    fun reset() = clear()

    @Synchronized
    fun purgeExpired() {
        if (activeCode?.isExpired() == true) activeCode = null
    }

    /**
     * Exchanges [presented] for a browser session. No approval round trip: a valid, unexpired,
     * not-yet-used code creates the session immediately.
     */
    @Synchronized
    fun exchange(
        presented: String,
        browserLabel: String,
        sourceIp: String,
    ): SessionCodeExchangeResult {
        val current = activeCode
        return when {
            current == null -> SessionCodeExchangeResult.Invalid
            current.isExpired() -> {
                activeCode = null
                SessionCodeExchangeResult.Expired
            }
            current.used || !constantTimeEquals(presented, current.code) -> SessionCodeExchangeResult.Invalid
            else -> {
                current.used = true
                // Immediately mint a replacement: the device surface (panel/QR/notification) keeps
                // polling currentInfo, and a consumed code left on screen is a URL that can never
                // connect. Single-use + one-live-at-a-time semantics are unchanged -- the
                // consumed code was already dead for any future exchange.
                issueCode()
                when (val result = sessionAuthority.createSession(browserLabel, sourceIp)) {
                    is SessionCreateResult.Created -> SessionCodeExchangeResult.Approved(result.session)
                    SessionCreateResult.SessionLimitReached -> SessionCodeExchangeResult.Rejected
                }
            }
        }
    }

    private fun randomCode(): String =
        CharArray(SESSION_CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.concatToString()

    private inner class ActiveCode(
        val code: String,
        val browserUrl: String,
        val expiresAtEpochMs: Long,
        var used: Boolean = false,
    ) {
        fun isExpired(): Boolean = nowEpochMs() >= expiresAtEpochMs
    }

    companion object {
        const val DEFAULT_SESSION_CODE_TTL_MS: Long = 5 * 60 * 1000L
        const val SESSION_CODE_LENGTH: Int = 8

        /**
         * Digits 2-9 plus uppercase letters, excluding 0/O, 1/I/L, and U/V -- glyphs that are easily
         * confused with each other or with a digit when read off a small screen.
         */
        const val CODE_ALPHABET: String = "23456789ABCDEFGHJKMNPQRSTWXYZ"

        private const val DEFAULT_LOOPBACK_HOST: String = "127.0.0.1"
        private const val DEFAULT_LOOPBACK_PORT: Int = 8080
    }
}
