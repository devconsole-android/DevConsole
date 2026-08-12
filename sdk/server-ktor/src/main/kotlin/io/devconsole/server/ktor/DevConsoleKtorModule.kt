package io.devconsole.server.ktor

import io.devconsole.api.CaptureRule
import io.devconsole.api.CaptureRuleEngine
import io.devconsole.api.ScreenshotResult
import io.devconsole.composer.ComposerBinaryBody
import io.devconsole.composer.ComposerBodyType
import io.devconsole.composer.ComposerCollectionStore
import io.devconsole.composer.ComposerCurlImporter
import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.ComposerMultipartPart
import io.devconsole.composer.ComposerQueryParameter
import io.devconsole.composer.ComposerRequest
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerVariable
import io.devconsole.composer.InMemoryComposerCollectionStore
import io.devconsole.composer.ResolvedComposerRequest
import io.devconsole.composer.UrlConnectionComposerTransport
import io.devconsole.export.DEFAULT_EXPORT_LIMIT_BYTES
import io.devconsole.export.EventExportWriter
import io.devconsole.export.EvidenceBundleAttachment
import io.devconsole.export.EvidenceBundleContent
import io.devconsole.export.ExportRequest
import io.devconsole.export.ExportResult
import io.devconsole.export.ExportScope
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.MockRuleStats
import io.devconsole.mocks.MockScope
import io.devconsole.network.BodyPreview
import io.devconsole.network.CaptureBodyMetadata
import io.devconsole.network.ExportSelection
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkExport
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkResponseMetadata
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionFilters
import io.devconsole.network.NetworkTransactionPage
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionStore
import io.devconsole.network.resolveExportSelection
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushInput
import io.devconsole.push.PushSimulator
import io.devconsole.push.PushStore
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BindingMode
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.CommandAuditEvent
import io.devconsole.server.api.CommandAuditLog
import io.devconsole.server.api.CommandAuditResult
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.Endpoint
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.LocalServerEngine
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import io.devconsole.server.api.SdkHealthSnapshot
import io.devconsole.server.api.ServerMetadata
import io.devconsole.server.api.ServerStartResult
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.server.api.SessionCodeExchangeResult
import io.devconsole.server.api.SessionCodeInfo
import io.devconsole.server.api.StartRequest
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.MqttFrameMetadata
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketFrameType
import io.devconsole.socket.SocketLifecycleEvent
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketMessageQuery
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketStore
import io.devconsole.state.SessionFeatureFlags
import io.devconsole.state.StateMutationResult
import io.devconsole.state.StateMutator
import io.devconsole.state.StateRegistry
import io.devconsole.state.StateSnapshot
import io.devconsole.state.StateValue
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceSeverity
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.RetainedCaptureQuery
import io.devconsole.storage.api.StoredAttachment
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import io.devconsole.storage.api.StoredSession
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.InMemoryTimelineAnnotations
import io.devconsole.timeline.Timeline
import io.devconsole.timeline.TimelineAnnotations
import io.devconsole.timeline.TimelineAppender
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery
import io.devconsole.timeline.TimelineSort
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.util.decodeBase64Bytes
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Mutable configuration for [Application.devConsoleModule], following the same
 * `install(Plugin) { ... }` DSL convention Ktor itself uses. Every property here defaults
 * to an in-memory implementation so callers only set the handful they need to override.
 */
class DevConsoleModuleConfig {
    var allowedHosts: Set<String> = setOf("localhost", "127.0.0.1")
    var streamHub: EventStreamHub = EventStreamHub()
    var timeline: Timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16).also(SecureRandom()::nextBytes)))
    var annotations: TimelineAnnotations = InMemoryTimelineAnnotations()
    var networkTransactions: NetworkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16).also(SecureRandom()::nextBytes)))
    var socketStore: SocketStore = InMemorySocketStore()
    var mockEngine: MockEngine = MockEngine(emptyList())

    /**
     * Mirrors `EditingCapabilities.mocks`. Listing rules and the global on/off status stay
     * available to any authenticated session; every rule-mutation route (create, delete, per-rule
     * enable/disable) answers 403 while this is false.
     */
    var mocksEditable: Boolean = false
    var captureRules: CaptureRuleEngine = CaptureRuleEngine()

    /**
     * Mirrors `EditingCapabilities.captureRules`. Listing exclusions stays available to any
     * authenticated session; every mutation route answers 403 while this is false.
     */
    var captureRulesEditable: Boolean = false
    var featureFlags: SessionFeatureFlags = SessionFeatureFlags(emptyList())

    /**
     * Mirrors `EditingCapabilities.featureFlags`. Listing flags stays available to any authenticated
     * session whenever the `state` capture category is enabled; the flag-override route answers 403
     * while this is false.
     */
    var featureFlagsEditable: Boolean = false
    var stateRegistry: StateRegistry = StateRegistry()
    var stateMutationsEnabled: Boolean = false

    /** Reads (file/entry listing) always work; [preferencesEditable] mirrors `EditingCapabilities.preferences`. */
    var preferencesInspector: PreferencesInspector? = null
    var preferencesEditable: Boolean = false

    /**
     * Reads (database/table listing, row paging, read-only SQL) always work; [databaseEditable] mirrors
     * `EditingCapabilities.database` and is only consulted by the SQL console -- a mutating statement is
     * refused with `DatabaseExecResult.WriteBlocked` while this is false, exactly like the Compose adapter.
     */
    var databaseInspector: DatabaseInspector? = null
    var databaseEditable: Boolean = false

    /** Reads (root/entry listing, bounded preview) always work; [filesEditable] mirrors `EditingCapabilities.files`. */
    var fileInspector: FileInspector? = null
    var filesEditable: Boolean = false
    var composerExecutor: ComposerExecutor = ComposerExecutor(UrlConnectionComposerTransport())
    var composerCollections: ComposerCollectionStore = InMemoryComposerCollectionStore()
    var pushStore: PushStore = InMemoryPushStore()
    var pushSimulator: PushSimulator? = null
    var commandAuditLog: CommandAuditLog = InMemoryCommandAuditLog()
    var metadata: ServerMetadata = ServerMetadata()
    var sdkHealth: () -> SdkHealthSnapshot = { SdkHealthSnapshot() }
    var boundEndpoint: () -> Endpoint? = { null }
    var redactionPolicy: RedactionPolicy = RedactionPolicy.default()
    var exportDirectory: File =
        File(System.getProperty("java.io.tmpdir"), "devconsole-exports")
    var attachmentReader: suspend (String) -> ByteArray? = { null }

    /**
     * Room metadata only (no bytes) -- backs the authoritative `redactionApplicability` reported by
     * the attachment route header and every JSON payload that embeds an `attachmentId`. Absent
     * (default) makes those surfaces omit the field rather than infer it.
     */
    var attachmentMetadataReader: suspend (String) -> StoredAttachment? = { null }
    var retainedCaptures: RetainedCaptureQuery? = null

    /**
     * Durable QA evidence tray. Null (the default) makes every `/api/v1/evidence` route answer
     * `EVIDENCE_UNAVAILABLE`.
     */
    var evidenceStore: EvidenceStore? = null

    /**
     * The active app-run's id, used to scope every evidence read/write. Defaults to a fixed literal
     * so a caller that never wires this up (most existing tests) still gets deterministic behavior.
     */
    var currentSessionId: () -> String = { "current" }

    /** Device/app metadata for the evidence bundle's `session.json`; null fields are honestly omitted. */
    var sessionSnapshotProvider: suspend () -> StoredSession? = { null }

    /**
     * Every retained run (this device's session history), backing `GET /api/v1/runs` -- the
     * previous-run-crashed banner's data source. Defaults to an empty history.
     */
    var sessionsProvider: suspend () -> List<StoredSession> = { emptyList() }

    /**
     * Delegates to `DevConsoleFacadeProvider.captureScreenshot()`. Defaults to always reporting the
     * SDK as disabled for this build, mirroring `ScreenshotResult.DisabledForBuild`'s own meaning.
     */
    var screenshotCapture: suspend () -> ScreenshotResult = { ScreenshotResult.DisabledForBuild }

    /**
     * The Composer lets an authenticated session make the device issue arbitrary outbound HTTP
     * requests, effectively turning it into a proxy into whatever network it sits on. It is off
     * unless a host opts in; every `/api/v1/composer` route answers 404 while disabled.
     */
    var composerEnabled: Boolean = false

    /**
     * Hosts the Composer may issue outbound requests to. Every destination, including redirects,
     * must be explicitly listed; an empty set denies all requests.
     */
    var composerAllowedHosts: Set<String> = emptySet()
}

// Two of the "throws" detekt counts here are the `throw cancelled` rethrows inside the Plugins- and
// Call-phase exception boundaries' catch blocks (see both `intercept(...)` calls below) -- each is a
// single-line, load-bearing rethrow of CancellationException, not error-prone exception-flow
// complexity, so raising the count is clearer than restructuring two independent boundaries to share
// a helper just to dodge this rule.
@Suppress("ThrowsCount")
fun Application.devConsoleModule(
    sessionAuthority: SessionAuthority,
    sessionCodeAuthority: SessionCodeAuthority = SessionCodeAuthority(sessionAuthority),
    configure: DevConsoleModuleConfig.() -> Unit = {},
) {
    val config = DevConsoleModuleConfig().apply(configure)
    val allowedHosts = config.allowedHosts
    val streamHub = config.streamHub
    val timeline = config.timeline
    val annotations = config.annotations
    val networkTransactions = config.networkTransactions
    val socketStore = config.socketStore
    val mockEngine = config.mockEngine
    val mocksEditable = config.mocksEditable
    val captureRules = config.captureRules
    val captureRulesEditable = config.captureRulesEditable
    val featureFlags = config.featureFlags
    val featureFlagsEditable = config.featureFlagsEditable
    val stateRegistry = config.stateRegistry
    val stateMutationsEnabled = config.stateMutationsEnabled
    val preferencesInspector = config.preferencesInspector
    val preferencesEditable = config.preferencesEditable
    val databaseInspector = config.databaseInspector
    val databaseEditable = config.databaseEditable
    val fileInspector = config.fileInspector
    val filesEditable = config.filesEditable
    val composerExecutor = config.composerExecutor
    val redactionPolicy = config.redactionPolicy
    val redaction = RedactionEngine(redactionPolicy)
    val composerCollections = RedactingComposerCollectionStore(config.composerCollections, redaction)
    val pushStore = config.pushStore
    val pushSimulator = config.pushSimulator
    val commandAuditLog = RedactingCommandAuditLog(config.commandAuditLog, redaction)
    val attachmentReader = config.attachmentReader
    val attachmentMetadataReader = config.attachmentMetadataReader
    val retainedCaptures = config.retainedCaptures
    val evidenceStore = config.evidenceStore
    val currentSessionId = config.currentSessionId
    val sessionSnapshotProvider = config.sessionSnapshotProvider
    val sessionsProvider = config.sessionsProvider
    val screenshotCapture = config.screenshotCapture
    val metadata = config.metadata
    val sdkHealth = config.sdkHealth
    val boundEndpoint = config.boundEndpoint
    val composerEnabled = config.composerEnabled
    val composerAllowedHosts = config.composerAllowedHosts
    val exportDirectory = config.exportDirectory
    // Shared by the session-code exchange route (POST /api/v1/auth/session-code/exchange), which
    // runs before any session exists and so must be keyed by source IP rather than session id.
    val sessionCodeAttemptLimiter = SlidingWindowRateLimiter(maxEvents = 5, windowMs = 60_000)
    val readQueryLimiter = SlidingWindowRateLimiter(maxEvents = 120, windowMs = 60_000)
    val composerLimiter = SlidingWindowRateLimiter(maxEvents = 20, windowMs = 60_000)
    val mockMutationLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    val captureRuleMutationLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    val preferencesMutationLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    val databaseSqlLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    val filesMutationLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    val stateMutationLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    val pushSimulationLimiter = SlidingWindowRateLimiter(maxEvents = 20, windowMs = 60_000)
    val exportLimiter = SlidingWindowRateLimiter(maxEvents = 5, windowMs = 10 * 60_000)
    val evidenceMutationLimiter = SlidingWindowRateLimiter(maxEvents = 30, windowMs = 60_000)
    // Screenshot capture does real work on the host (PixelCopy, encode, storage write); a tighter
    // ceiling than the general mutation limiters keeps a runaway client from hammering it.
    val screenshotLimiter = SlidingWindowRateLimiter(maxEvents = 10, windowMs = 60_000)
    // Full-body network exports (whole capture history rendered as HAR/Postman JSON) are heavier
    // than a typical paginated GET, so they get their own, tighter ceiling instead of riding the
    // generic 120/min read-query bucket.
    val networkExportLimiter = SlidingWindowRateLimiter(maxEvents = 10, windowMs = 60_000)
    val socketSendLimiter = SlidingWindowRateLimiter(maxEvents = 60, windowMs = 60_000)
    install(WebSockets) {
        maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
        // Without a heartbeat, a client that vanishes without a clean close (killed app, dropped
        // network) leaks its per-session collector coroutine (see the /api/v1/stream route below)
        // until the OS eventually errors the socket out from under it. A 30s ping / 60s timeout
        // bounds that leak to at most two missed pings' worth of time.
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
    // Global exception boundary, part 1: everything in this Plugins-phase interceptor -- host header
    // parsing, CSP construction, session lookup, rate limiting -- runs *before* the Call-phase
    // boundary below (Plugins is upstream of Call in ApplicationCallPipeline's fixed phase order), so
    // a throw here would reach the CIO engine's default handler unfiltered -- the same leak the
    // Call-phase boundary exists to prevent. Wrapped the same way: rethrow cancellation, log, and
    // reply with the same bounded JSON envelope. Unlike the Call-phase boundary, `finish()` is also
    // called here after a caught exception -- with `routing { }` and its handlers still ahead in the
    // pipeline, letting this interceptor return normally would let them run against a call whose
    // response may already be committed.
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("X-Frame-Options", "DENY")
            call.response.headers.append("X-XSS-Protection", "0")
            // Parsed here (rather than down by the ORIGIN_REJECTED check below, where this used to live)
            // because the CSP built just below needs it too. Uses parseHostHeader() rather than the raw
            // header text -- interpolating the raw `Host` value into a response header is exactly how a
            // client that controls it (curl, any on-device HTTP client; Host is a forbidden header for
            // browser fetch/XHR but this SDK's threat model includes non-browser clients) injects its own
            // CSP directives: `Host: localhost:4321; frame-ancestors 'self' https://evil.example.com`
            // has an allowed pre-colon name, but reflecting the tail verbatim would smuggle a whole
            // extra `frame-ancestors` directive into the header, overriding X-Frame-Options: DENY. Only
            // a value that parses cleanly as `host[:port]` (numeric, in-range port) is ever allowed
            // through; anything else is treated the same as a disallowed host.
            val parsedHost = parseHostHeader(call.request.headers[HttpHeaders.Host].orEmpty())
            val host = parsedHost?.name?.lowercase().orEmpty()
            val hostAllowed = parsedHost != null && host in allowedHosts
            // dashboard.js always dials back to `location.host` over ws:// (this embedded server never
            // terminates TLS, so wss: is never actually reachable) -- see its stream-connect code. A
            // bare 'self' *should* cover that same-origin ws per the CSP3 same-origin-for-ws-schemes
            // rule, but Safari has historically failed to treat 'self' as matching a ws:// scheme
            // against an http: page, so the host:port is spelled out explicitly here too -- taken from
            // this request's own (already-validated-below) Host header, not from boundEndpoint(). Those
            // two can differ: `allowedHosts` deliberately accepts both "localhost" (the documented `adb
            // reverse` workflow) and the bind address itself, e.g. "127.0.0.1", so a browser sitting at
            // http://localhost:<port> has `location.host == "localhost:<port>"` while
            // boundEndpoint() reports the bind address "127.0.0.1:<port>" -- pinning connect-src to the
            // latter would silently block the former's WebSocket upgrade. A request whose Host isn't in
            // allowedHosts at all is about to be rejected outright below, so it never gets reflected here.
            // This narrows the prior blanket "ws: wss:" (any host, any scheme) -- the exact channel a
            // future XSS would use to exfiltrate to an attacker-controlled WebSocket server -- down to
            // just the one endpoint the dashboard is actually allowed to talk to.
            val webSocketSource =
                if (hostAllowed) {
                    val uriHost = if (':' in host) "[$host]" else host // re-bracket an IPv6 literal
                    val portSuffix = parsedHost.port?.let { ":$it" }.orEmpty()
                    "'self' ws://$uriHost$portSuffix"
                } else {
                    "'self'"
                }
            call.response.headers.append(
                "Content-Security-Policy",
                // Dashboard script now ships as an external /assets/dashboard.js file with zero inline
                // handlers (event wiring happens via .onclick= property assignment in JS, not on*
                // attributes), so script-src no longer needs 'unsafe-inline'.
                //
                // base-uri 'self' is the most important of the three additions below: without it, an
                // injected <base href="https://evil.example.com/"> reparents every path-absolute
                // fetch/script/link on the page to the attacker's origin -- exactly how the in-memory
                // Bearer token this dashboard holds would leave. frame-ancestors 'none' is the CSP3
                // (iframe-aware) replacement for the X-Frame-Options: DENY already sent above, kept
                // for older-browser compatibility. form-action 'none' is safe to set unconditionally:
                // the dashboard has no <form> elements and never submits one natively (see
                // dashboard.js/index.html -- every mutation goes through fetch()).
                "default-src 'self'; script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src $webSocketSource; " +
                    "base-uri 'self'; frame-ancestors 'none'; form-action 'none'",
            )
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")

            if (!hostAllowed) {
                call.respondText("{\"code\":\"ORIGIN_REJECTED\"}", status = HttpStatusCode.Forbidden)
                finish()
                return@intercept
            }
            val path = call.request.uri.substringBefore('?')
            val method = call.request.httpMethod.value
            val browserSession =
                call.request.headers[HttpHeaders.Authorization]
                    .orEmpty()
                    .removePrefix("Bearer ")
                    .takeIf { it.isNotBlank() }
                    ?.let { token -> sessionAuthority.sessionForToken(token) }
            if (!composerEnabled && path.startsWith("/api/v1/composer")) {
                browserSession
                    ?.let { session ->
                        path.commandAuditIdentity(method)?.let { (commandType, target) ->
                            commandAuditLog.recordControlFailure(session.id, commandType, target)
                        }
                    }
                call.respondText("{\"code\":\"COMPOSER_DISABLED\"}", status = HttpStatusCode.NotFound)
                finish()
                return@intercept
            }
            val limiter =
                browserSession?.let {
                    when {
                        path == "/api/v1/composer/execute" -> composerLimiter
                        path.startsWith("/api/v1/network/transactions/") && path.endsWith("/resend") -> composerLimiter
                        path.startsWith("/api/v1/mocks") && method != "GET" -> mockMutationLimiter
                        path.startsWith("/api/v1/capture-rules") && method != "GET" -> captureRuleMutationLimiter
                        path.startsWith("/api/v1/preferences") && method != "GET" -> preferencesMutationLimiter
                        path.startsWith("/api/v1/database") && method == "POST" -> databaseSqlLimiter
                        path.startsWith("/api/v1/files") && method != "GET" -> filesMutationLimiter
                        path.contains("/mutations/") -> stateMutationLimiter
                        path == "/api/v1/push/simulate" -> pushSimulationLimiter
                        path == "/api/v1/exports" && method == "POST" -> exportLimiter
                        // GET .../estimate does the identical evidence-bundle assembly work POST
                        // .../exports does (see buildEvidenceExportRequest) -- it must share the same
                        // tight budget, not the 120/min readQueryLimiter below.
                        path == "/api/v1/exports/estimate" && method == "GET" -> exportLimiter
                        path.startsWith("/api/v1/evidence") && method != "GET" -> evidenceMutationLimiter
                        path == "/api/v1/screenshots" && method == "POST" -> screenshotLimiter
                        path.startsWith(
                            "/api/v1/websockets/connections/",
                        ) &&
                            path.endsWith("/send") -> socketSendLimiter
                        (path == "/api/v1/network/har" || path == "/api/v1/network/postman") &&
                            (method == "GET" || method == "POST") ->
                            networkExportLimiter
                        method == "GET" && path.startsWith("/api/v1/") -> readQueryLimiter
                        else -> null
                    }
                }
            if (limiter != null && !limiter.allow(browserSession.id)) {
                path.commandAuditIdentity(method)?.let { (commandType, target) ->
                    commandAuditLog.recordControlFailure(browserSession.id, commandType, target)
                }
                call.respondText("{\"code\":\"RATE_LIMITED\"}", status = HttpStatusCode.TooManyRequests)
                finish()
                return@intercept
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            call.application.environment.log.error(
                "Unhandled exception in the Plugins-phase interceptor for " +
                    "${call.request.httpMethod.value} ${call.request.uri}",
                throwable,
            )
            if (!call.response.isCommitted) {
                call.respondText(
                    "{\"code\":\"INTERNAL_ERROR\"}",
                    status = HttpStatusCode.InternalServerError,
                    contentType = io.ktor.http.ContentType.Application.Json,
                )
            }
            finish()
        }
    }
    // Global exception boundary: a host-supplied inspector (database/file) or any other route
    // handler can throw -- without this, that exception would otherwise reach the CIO engine's
    // default handler, which can leak the exception's message/stack trace to the browser and
    // breaks the API's JSON-only contract. `ktor-server-status-pages` is not on this module's
    // classpath, so this wraps the same phase that plugin installs into (ApplicationCallPipeline.Call,
    // immediately downstream of routing dispatch) by hand: intercepting here, before `routing { }`
    // registers its own Call-phase interceptor, means `proceed()` below runs the entire route match
    // and handler, so any exception it throws is caught right here.
    intercept(ApplicationCallPipeline.Call) {
        try {
            proceed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (mediaType: UnsupportedMediaTypeException) {
            // A malformed request is the caller's mistake; answering 500 sends them hunting a server bug.
            call.respondClientError(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", mediaType)
        } catch (malformed: ContentTransformationException) {
            call.respondClientError(HttpStatusCode.BadRequest, "VALIDATION_FAILED", malformed)
        } catch (badRequest: BadRequestException) {
            call.respondClientError(HttpStatusCode.BadRequest, "VALIDATION_FAILED", badRequest)
        } catch (throwable: Throwable) {
            call.application.environment.log.error(
                "Unhandled exception handling ${call.request.httpMethod.value} ${call.request.uri}",
                throwable,
            )
            if (!call.response.isCommitted) {
                call.respondText(
                    "{\"code\":\"INTERNAL_ERROR\"}",
                    status = HttpStatusCode.InternalServerError,
                    contentType = io.ktor.http.ContentType.Application.Json,
                )
            }
        }
    }

    // Capture-category gating: fail-open by design, matching every other capability check in this
    // module -- a metadata snapshot that (for whatever reason) doesn't carry a `captureCategories`
    // list, or a lookup that throws, must never turn into a route-wide outage. A stale dashboard tab
    // whose host disabled a category server-side gets a clean 403 instead of quietly reading data
    // the host never wanted captured.
    fun categoryEnabled(name: String): Boolean =
        runCatching { metadata.captureCategories.contains(name) }.getOrDefault(true)

    suspend fun io.ktor.server.application.ApplicationCall.respondCategoryDisabled(name: String) {
        respondText(
            "{\"error\":{\"code\":\"CATEGORY_DISABLED\"," +
                "\"message\":\"The '${name.escapeJson()}' capture category is disabled for this app run\"}}",
            status = HttpStatusCode.Forbidden,
            contentType = io.ktor.http.ContentType.Application.Json,
        )
    }
    routing {
        get("/") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(DashboardAssets.index(), contentType = io.ktor.http.ContentType.Text.Html)
        }
        // Static dashboard assets: no session-specific data is baked into either file, so they are
        // served unauthenticated just like GET / -- the browser needs them before any session exists.
        get("/assets/dashboard.css") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(DashboardAssets.css(), contentType = io.ktor.http.ContentType.Text.CSS)
        }
        get("/assets/dashboard.js") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(DashboardAssets.js(), contentType = io.ktor.http.ContentType.Text.JavaScript)
        }
        // Browsers request the favicon before -- and independently of -- any session, so it is
        // unauthenticated for the same reason the two assets above are.
        get("/assets/favicon.webp") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(DashboardAssets.favicon(), contentType = io.ktor.http.ContentType("image", "webp"))
        }
        get("/health") {
            call.respondText(
                "{\"status\":\"auth_required\",\"protocolVersion\":${metadata.protocolVersion}," +
                    "\"appDisplayName\":\"${metadata.appDisplayName.escapeJson()}\"}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/meta") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val endpoint = boundEndpoint()
            val endpointJson =
                endpoint?.let {
                    "{\"host\":\"${it.host.escapeJson()}\",\"port\":${it.port}," +
                        "\"bindingMode\":\"${it.bindingMode.name}\"}"
                }
                    ?: "null"
            val redactedNames = redactionPolicy.sensitiveFieldNames.joinToString(",") { "\"${it.escapeJson()}\"" }
            val allowedHosts = composerAllowedHosts.sorted().joinToString(",") { "\"${it.escapeJson()}\"" }
            val captureCategories =
                metadata.captureCategories.joinToString(",") { "\"${it.escapeJson()}\"" }
            val body =
                metadata.json().dropLast(1) +
                    ",\"endpoint\":$endpointJson,\"redaction\":{\"sensitiveFieldNames\":[$redactedNames]}," +
                    "\"composer\":{\"enabled\":$composerEnabled,\"allowedHosts\":[$allowedHosts]}," +
                    "\"captureCategories\":[$captureCategories]}"
            call.respondText(body, contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/sdk-health") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val health = sdkHealth().copy(activePrincipalCount = sessionAuthority.principals().size)
            call.respondText(health.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/overview") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val health = sdkHealth().copy(activePrincipalCount = sessionAuthority.principals().size)
            val distribution =
                networkTransactions.statusDistribution().entries.joinToString(
                    ",",
                ) { (bucket, count) -> "\"${bucket.escapeJson()}\":$count" }
            val body =
                "{\"app\":${metadata.appJson()}," +
                    "\"mocks\":{\"enabled\":${mockEngine.isEnabled()},\"ruleCount\":${mockEngine.rules().size}}," +
                    "\"sdkHealth\":${health.json()},\"networkStatusDistribution\":{$distribution}," +
                    "\"sessionIntegrity\":${sessionIntegrityJson(mockEngine, featureFlags, commandAuditLog)}}"
            call.respondText(body, contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/auth/session-code/exchange") {
            // Keyed by source IP rather than session id: exchange runs before any session exists.
            if (!sessionCodeAttemptLimiter.allow(call.request.origin.remoteHost)) {
                call.respondText("{\"code\":\"RATE_LIMITED\"}", status = HttpStatusCode.TooManyRequests)
                return@post
            }
            val parameters = call.receiveParameters()
            val code = parameters["code"].orEmpty()
            if (code.isBlank()) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            when (
                val result =
                    sessionCodeAuthority.exchange(
                        presented = code,
                        browserLabel = parameters["browserLabel"] ?: "Unknown browser",
                        sourceIp = call.request.origin.remoteHost,
                    )
            ) {
                is SessionCodeExchangeResult.Approved -> {
                    call.setStreamSessionCookie(result.session.token)
                    call.respondText(
                        "{\"accessToken\":\"${result.session.token}\",\"csrfToken\":\"${result.session.csrfToken}\"}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                }
                SessionCodeExchangeResult.Rejected ->
                    call.respondText(
                        "{\"code\":\"SESSION_CODE_SESSION_LIMIT\"}",
                        status = HttpStatusCode.Conflict,
                    )
                SessionCodeExchangeResult.Expired ->
                    call.respondText("{\"code\":\"SESSION_CODE_EXPIRED\"}", status = HttpStatusCode.Unauthorized)
                SessionCodeExchangeResult.Invalid ->
                    call.respondText("{\"code\":\"SESSION_CODE_INVALID\"}", status = HttpStatusCode.Unauthorized)
            }
        }
        // Lets an already-authenticated browser mint a fresh session code -- e.g. to hand a QR/link
        // to a second device -- without stopping and restarting the server. issueCode() immediately
        // invalidates whatever code was previously live, exactly like the one issued at server start.
        post("/api/v1/auth/session-code/rotate") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "session.code.rotate", "session-code")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val endpoint = boundEndpoint()
            val rotated =
                if (endpoint != null) sessionCodeAuthority.issueCode(endpoint) else sessionCodeAuthority.issueCode()
            commandAuditLog.recordControlSuccess(session.id, "session.code.rotate", "session-code")
            call.respondText(rotated.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/auth/refresh") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val refreshed =
                sessionAuthority.refresh(
                    call.request.headers[HttpHeaders.Authorization]
                        .orEmpty()
                        .removePrefix("Bearer "),
                )
            if (refreshed == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            call.setStreamSessionCookie(refreshed.token)
            call.respondText(
                "{\"accessToken\":\"${refreshed.token}\",\"csrfToken\":\"${refreshed.csrfToken}\",\"expiresAtEpochMs\":${refreshed.expiresAtEpochMs}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/auth/logout") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            sessionAuthority.revokeIfPresent(session.id)
            call.clearStreamSessionCookie()
            call.respondText("", status = HttpStatusCode.NoContent)
        }
        // Unified with every other authenticated route: previously ADMIN-only, now any
        // authenticated session may list/revoke principals, consistent with the single access tier.
        get("/api/v1/auth/principals") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val principals =
                sessionAuthority.principals().joinToString(",") { principal ->
                    "{\"id\":\"${principal.id.escapeJson()}\",\"browserLabel\":\"${principal.browserLabel.escapeJson()}\",\"sourceIp\":\"${principal.sourceIp.escapeJson()}\",\"expiresAtEpochMs\":${principal.expiresAtEpochMs}}"
                }
            call.respondText("{\"data\":[$principals]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/auth/principals/{id}") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            val principalId = call.parameters["id"].orEmpty()
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@delete
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "session.revoke", principalId.ifBlank { "principal" })
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@delete
            }
            if (!sessionAuthority.revokeIfPresent(principalId)) {
                commandAuditLog.recordControlFailure(session.id, "session.revoke", principalId.ifBlank { "principal" })
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@delete
            }
            commandAuditLog.recordControlSuccess(session.id, "session.revoke", principalId)
            call.respondText("", status = HttpStatusCode.NoContent)
        }
        get("/api/v1/session") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            call.respondText(
                "{\"expiresAtEpochMs\":${session.expiresAtEpochMs}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        // Retained app-run history (the "previous-run banner": the Overview needs the most recent
        // *non-active* run's StoredSessionStatus to know whether it CRASHED). Named "runs" rather
        // than reusing "session" -- that word is already spoken for by this dashboard's own bearer
        // session (see GET /api/v1/session just above) and would be ambiguous here. Read-only, bearer
        // auth only like every other GET; there is nothing to mutate.
        get("/api/v1/runs") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            // Sorted here rather than trusted from the provider: newest-first, deterministically
            // tie-broken by id, regardless of what order the underlying store happens to hand back.
            val runs =
                sessionsProvider()
                    .sortedWith(compareByDescending<StoredSession> { it.startedAtMs }.thenByDescending { it.id })
            call.respondText(
                "{\"data\":[${runs.joinToString(",") { it.json() }}]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/report") {
            val session =
                sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!exportLimiter.allow(session.id)) {
                call.respondText("{\"code\":\"RATE_LIMITED\"}", status = HttpStatusCode.TooManyRequests)
                return@get
            }
            val limit =
                call.request.queryParameters["limit"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, TimelineQuery.MAX_PAGE_LIMIT)
                    ?: TimelineQuery.MAX_PAGE_LIMIT
            val page = timeline.page(TimelineQuery(limit = limit, sort = TimelineSort.DESC))
            val events = (page as? TimelinePage.Success)?.events.orEmpty()
            val transactions =
                networkTransactions.page(NetworkTransactionQuery(limit = NetworkTransactionQuery.MAX_PAGE_LIMIT))
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"devconsole-report.json\"",
            )
            call.respondText(
                buildString {
                    append("{\"generatedAtEpochMs\":").append(System.currentTimeMillis())
                    append(",\"app\":").append(metadata.appJson())
                    append(",\"build\":{\"variant\":\"").append(metadata.buildVariant.escapeJson()).append("\"}")
                    append(",\"sessionIntegrity\":")
                        .append(sessionIntegrityJson(mockEngine, featureFlags, commandAuditLog))
                    append(",\"sdkHealth\":").append(sdkHealth().json())
                    append(",\"timeline\":[").append(events.joinToString(",") { it.reportJson() }).append(']')
                    append(",\"network\":[")
                        .append(transactions.transactions.joinToString(",") { it.summaryJson() })
                        .append(']')
                    append('}')
                },
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/exports") {
            val session =
                sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordExport(session.id, CommandAuditResult.REJECTED, "csrf")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val receivedParameters = call.receiveParameters()
            if ((receivedParameters["scope"] ?: "").uppercase() == "EVIDENCE") {
                val maxBytes = receivedParameters.resolveMaxBytes()
                if (maxBytes == null) {
                    commandAuditLog.recordExport(session.id, CommandAuditResult.REJECTED, "validation")
                    call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                    return@post
                }
                pruneStaleExports(exportDirectory)
                val evidenceRequest =
                    buildEvidenceExportRequest(
                        sessionId = currentSessionId(),
                        maxBytes = maxBytes,
                        destination = File(exportDirectory, "devconsole-evidence-${UUID.randomUUID()}.zip"),
                        evidenceStore = evidenceStore,
                        networkTransactions = networkTransactions,
                        metadata = metadata,
                        attachmentReader = attachmentReader,
                        attachmentMetadataReader = attachmentMetadataReader,
                        sessionSnapshotProvider = sessionSnapshotProvider,
                        redaction = redaction,
                    )
                if (evidenceRequest == null) {
                    commandAuditLog.recordExport(session.id, CommandAuditResult.FAILED, "unavailable")
                    call.respondText("{\"code\":\"EXPORT_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                val writer = EventExportWriter(redaction)
                when (val result = writer.writeAsync(evidenceRequest)) {
                    is ExportResult.Success -> {
                        commandAuditLog.recordExport(session.id, CommandAuditResult.SUCCESS, "Evidence")
                        call.response.headers.append(
                            HttpHeaders.ContentDisposition,
                            "attachment; filename=\"devconsole-evidence.zip\"",
                        )
                        call.respondOutputStream(
                            contentType = ContentType.parse("application/zip"),
                            contentLength = result.file.length(),
                        ) {
                            try {
                                result.file.inputStream().use { input -> input.copyTo(this) }
                            } finally {
                                result.file.delete()
                            }
                        }
                    }
                    ExportResult.ExceedsSizeLimit -> {
                        commandAuditLog.recordExport(session.id, CommandAuditResult.REJECTED, "size-limit")
                        call.respondText(
                            "{\"code\":\"EXPORT_TOO_LARGE\",\"maxBytes\":$maxBytes," +
                                "\"estimatedBytes\":${writer.estimateBytes(evidenceRequest)}," +
                                "\"guidance\":\"${ExportResult.ExceedsSizeLimit.GUIDANCE.escapeJson()}\"}",
                            contentType = io.ktor.http.ContentType.Application.Json,
                            status = HttpStatusCode.PayloadTooLarge,
                        )
                    }
                    ExportResult.Unavailable -> {
                        commandAuditLog.recordExport(session.id, CommandAuditResult.FAILED, "unavailable")
                        call.respondText(
                            "{\"code\":\"EXPORT_UNAVAILABLE\"}",
                            status = HttpStatusCode.ServiceUnavailable,
                        )
                    }
                }
                return@post
            }
            val prepared =
                prepareExport(receivedParameters, timeline, retainedCaptures, attachmentReader)
            if (prepared == null) {
                commandAuditLog.recordExport(session.id, CommandAuditResult.REJECTED, "validation")
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            pruneStaleExports(exportDirectory)
            val request =
                ExportRequest(
                    sessionId = prepared.exportSessionId,
                    events = prepared.scopedEvents,
                    destination = File(exportDirectory, "devconsole-${UUID.randomUUID()}.zip"),
                    maxBytes = prepared.maxBytes,
                ).withScope(prepared.scope)
                    .withMetadataOnly(prepared.metadataOnly)
                    .withAnnotations(prepared.scopedEvents.associate { it.id to annotations.get(it.id) })
                    .withAttachments(prepared.attachments)
            val writer = EventExportWriter(redaction)
            when (val result = writer.writeAsync(request)) {
                is ExportResult.Success -> {
                    commandAuditLog.recordExport(
                        session.id,
                        CommandAuditResult.SUCCESS,
                        prepared.scope.javaClass.simpleName,
                    )
                    // Mirror AndroidInspectorExporter.exportSessionZip: the browser bundle carries the
                    // same network.har / network.postman_collection.json / metadata.json the on-device
                    // session ZIP includes, generated over this session's captured transactions.
                    val sessionTransactions =
                        networkTransactions
                            .resolveExportSelection(ExportSelection.All)
                            ?.filter { it.sessionId == prepared.exportSessionId }
                            .orEmpty()
                    val bundle = File(exportDirectory, "devconsole-bundle-${UUID.randomUUID()}.zip")
                    try {
                        appendSessionBundleEntries(result.file, bundle, sessionTransactions, metadata, redaction)
                    } catch (failure: Throwable) {
                        // A merge failure must not leak either temp file; pruneStaleExports would only
                        // reclaim them an hour later. The partial bundle is deleted here; result.file
                        // is deleted by the shared finally below.
                        bundle.delete()
                        throw failure
                    } finally {
                        result.file.delete()
                    }
                    call.response.headers.append(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"devconsole-export.zip\"",
                    )
                    call.respondOutputStream(
                        contentType = ContentType.parse("application/zip"),
                        contentLength = bundle.length(),
                    ) {
                        try {
                            bundle.inputStream().use { input -> input.copyTo(this) }
                        } finally {
                            bundle.delete()
                        }
                    }
                }
                ExportResult.ExceedsSizeLimit -> {
                    commandAuditLog.recordExport(session.id, CommandAuditResult.REJECTED, "size-limit")
                    call.respondText(
                        "{\"code\":\"EXPORT_TOO_LARGE\",\"maxBytes\":${prepared.maxBytes}," +
                            "\"estimatedBytes\":${writer.estimateBytes(request)}," +
                            "\"guidance\":\"${ExportResult.ExceedsSizeLimit.GUIDANCE.escapeJson()}\"}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.PayloadTooLarge,
                    )
                }
                ExportResult.Unavailable -> {
                    commandAuditLog.recordExport(session.id, CommandAuditResult.FAILED, "unavailable")
                    call.respondText(
                        "{\"code\":\"EXPORT_UNAVAILABLE\"}",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                }
            }
        }
        get("/api/v1/exports/estimate") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if ((call.request.queryParameters["scope"] ?: "").uppercase() == "EVIDENCE") {
                val maxBytes = call.request.queryParameters.resolveMaxBytes()
                if (maxBytes == null) {
                    call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                    return@get
                }
                val evidenceRequest =
                    buildEvidenceExportRequest(
                        sessionId = currentSessionId(),
                        maxBytes = maxBytes,
                        destination = File(exportDirectory, "devconsole-evidence-estimate-${UUID.randomUUID()}.zip"),
                        evidenceStore = evidenceStore,
                        networkTransactions = networkTransactions,
                        metadata = metadata,
                        attachmentReader = attachmentReader,
                        attachmentMetadataReader = attachmentMetadataReader,
                        sessionSnapshotProvider = sessionSnapshotProvider,
                        redaction = redaction,
                    )
                if (evidenceRequest == null) {
                    call.respondText("{\"code\":\"EXPORT_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                    return@get
                }
                call.respondText(
                    "{\"estimatedBytes\":${EventExportWriter(redaction).estimateBytes(evidenceRequest)}}",
                    contentType = io.ktor.http.ContentType.Application.Json,
                )
                return@get
            }
            val prepared =
                prepareExport(call.request.queryParameters, timeline, retainedCaptures, attachmentReader)
            if (prepared == null) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@get
            }
            val request =
                ExportRequest(
                    sessionId = prepared.exportSessionId,
                    events = prepared.scopedEvents,
                    destination = File(exportDirectory, "devconsole-estimate-${UUID.randomUUID()}.zip"),
                    maxBytes = prepared.maxBytes,
                ).withScope(prepared.scope)
                    .withMetadataOnly(prepared.metadataOnly)
                    .withAnnotations(prepared.scopedEvents.associate { it.id to annotations.get(it.id) })
                    .withAttachments(prepared.attachments)
            val estimatedBytes = EventExportWriter(redaction).estimateBytes(request)
            call.respondText(
                "{\"estimatedBytes\":$estimatedBytes}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/session/integrity") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            call.respondText(
                sessionIntegrityJson(mockEngine, featureFlags, commandAuditLog),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/session/integrity/reset") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (!call.isReadMutationAuthorized(sessionAuthority)) {
                commandAuditLog.recordControlFailure(session.id, "session.integrity.reset", "session")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            mockEngine.setEnabled(false)
            mockEngine.clearSessionRules()
            featureFlags.reset()
            commandAuditLog.recordControlSuccess(session.id, "session.integrity.reset", "session")
            call.respondText(
                sessionIntegrityJson(mockEngine, featureFlags, commandAuditLog),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/events") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: TimelineQuery.DEFAULT_PAGE_LIMIT
            val sort =
                runCatching {
                    TimelineSort.valueOf(
                        call.request.queryParameters["sort"]?.uppercase() ?: TimelineSort.ASC.name,
                    )
                }.getOrNull()
            if (sort == null || limit !in 1..TimelineQuery.MAX_PAGE_LIMIT) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@get
            }
            val fromEpochMs =
                call.request.queryParameters.strictLong("from")
                    ?: if ("from" in call.request.queryParameters) {
                        call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                        return@get
                    } else {
                        null
                    }
            val toEpochMs =
                call.request.queryParameters.strictLong("to")
                    ?: if ("to" in call.request.queryParameters) {
                        call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                        return@get
                    } else {
                        null
                    }
            if (
                listOfNotNull(fromEpochMs, toEpochMs).any { it < 0 } ||
                (fromEpochMs != null && toEpochMs != null && fromEpochMs > toEpochMs)
            ) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@get
            }
            val page =
                timeline.page(
                    TimelineQuery(
                        limit = limit,
                        cursor = call.request.queryParameters["cursor"],
                        pluginIds =
                            call.request.queryParameters
                                .getAll("pluginId")
                                ?.toSet()
                                .orEmpty(),
                        types =
                            call.request.queryParameters
                                .getAll("type")
                                ?.toSet()
                                .orEmpty(),
                        severities =
                            call.request.queryParameters
                                .getAll("severity")
                                ?.mapNotNull(String::toIntOrNull)
                                ?.toSet()
                                .orEmpty(),
                        correlationId = call.request.queryParameters["correlationId"],
                        query = call.request.queryParameters["query"],
                        sort = sort,
                    ).withTimeRange(fromEpochMs, toEpochMs),
                )
            when (page) {
                TimelinePage.InvalidCursor ->
                    call.respondText(
                        "{\"code\":\"VALIDATION_FAILED\"}",
                        status = HttpStatusCode.BadRequest,
                    )
                is TimelinePage.Success -> {
                    val data =
                        page.events.joinToString(",") { event ->
                            "{\"id\":\"${event.id.escapeJson()}\",\"sequence\":${event.sequence}," +
                                "\"wallTimeMs\":${event.wallTimeMs},\"monotonicNanos\":${event.monoTimeNs}," +
                                "\"severity\":${event.severity},\"pluginId\":\"${event.pluginId.escapeJson()}\"," +
                                "\"type\":\"${event.type.escapeJson()}\"," +
                                "\"summary\":\"${event.summary.escapeJson()}\"," +
                                "\"correlationId\":${event.correlationId?.let {
                                    "\"${it.escapeJson()}\""
                                } ?: "null"},\"tags\":${event.tagsJson}}"
                        }
                    val cursor = page.nextCursor?.let { "\"${it.escapeJson()}\"" } ?: "null"
                    call.respondText(
                        "{\"data\":[$data],\"page\":{\"nextCursor\":$cursor,\"hasMore\":${page.hasMore}}}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                }
            }
        }
        get("/api/v1/network/transactions") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@get
            }
            val query =
                call.networkTransactionQuery() ?: run {
                    call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                    return@get
                }
            call.respondNetworkTransactionPage(networkTransactions.page(query))
        }
        get("/api/v1/retained-events") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val requestedSessionId = call.request.queryParameters["sessionId"]?.takeIf(String::isNotBlank)
            val rawLimit = call.request.queryParameters["limit"]
            val limit = rawLimit?.toIntOrNull() ?: RetainedCaptureQuery.DEFAULT_LIMIT
            // Repeatable ?pluginId=a&pluginId=b, matching /api/v1/events' convention (TimelineQuery.pluginIds).
            // Blank entries are dropped; if nothing non-blank survives this is indistinguishable from the
            // parameter being absent, so behavior falls back to the pre-existing single-session read below.
            val pluginIds =
                call.request.queryParameters
                    .getAll("pluginId")
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    .orEmpty()
            if (
                requestedSessionId != null &&
                requestedSessionId.length > MAX_EXPORT_SESSION_ID_LENGTH ||
                rawLimit != null &&
                rawLimit.toIntOrNull() == null ||
                limit !in 1..RetainedCaptureQuery.MAX_LIMIT
            ) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@get
            }
            val events = retainedCaptures?.events(requestedSessionId, limit, pluginIds).orEmpty()
            // .map { }.joinToString(",") rather than joinToString(",") { } -- joinToString's
            // transform parameter is a plain (T) -> CharSequence, not inline, so a suspend call
            // (the attachment metadata lookup below) cannot happen inside it; map's transform is
            // inline and suspends fine from this route handler's own coroutine.
            val data = events.map { event -> event.retainedJson(attachmentMetadataReader) }.joinToString(",")
            call.respondText(
                "{\"data\":[$data],\"limit\":$limit}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/mocks") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("mocks")) {
                call.respondCategoryDisabled("mocks")
                return@get
            }
            call.respondText(
                "{\"enabled\":${mockEngine.isEnabled()}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/mocks/disable-all") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "mock.disable_all", "mocks")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "mock.disable_all", "mocks")
                call.respondCategoryDisabled("mocks")
                return@post
            }
            mockEngine.setEnabled(false)
            commandAuditLog.recordControlSuccess(session.id, "mock.disable_all", "mocks")
            call.respondText("{\"enabled\":false}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        // The way back from `disable-all`. Capability-gated, unlike it: turning mocking ON changes
        // how the app behaves.
        post("/api/v1/mocks/enabled") {
            val session =
                call.mockRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    mocksEditable,
                    "mock.enabled",
                    "mocks",
                ) ?: return@post
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "mock.enabled", "mocks")
                call.respondCategoryDisabled("mocks")
                return@post
            }
            val enabled =
                call
                    .receiveText()
                    .trim()
                    .trim('"')
                    .toBooleanStrictOrNull()
            if (enabled == null) {
                commandAuditLog.recordControlFailure(session.id, "mock.enabled", "mocks")
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            mockEngine.setEnabled(enabled)
            commandAuditLog.recordControlSuccess(
                session.id,
                "mock.enabled",
                "mocks",
                mapOf("enabled" to enabled.toString()),
            )
            call.respondText(
                "{\"enabled\":${mockEngine.isEnabled()}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/mocks/rules") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("mocks")) {
                call.respondCategoryDisabled("mocks")
                return@get
            }
            val rules = mockEngine.rules().joinToString(",") { it.json(mockEngine.stats(it.id)) }
            call.respondText(
                "{\"editable\":$mocksEditable,\"data\":[$rules]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/mocks/conflicts") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("mocks")) {
                call.respondCategoryDisabled("mocks")
                return@get
            }
            val conflicts =
                mockEngine.conflicts().joinToString(",") { (first, second) ->
                    "{\"first\":\"${first.id.escapeJson()}\",\"second\":\"${second.id.escapeJson()}\"}"
                }
            call.respondText("{\"data\":[$conflicts]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/mocks/rules") {
            val session =
                call.mockRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    mocksEditable,
                    "mock.rule.upsert",
                    "rule",
                )
                    ?: return@post
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.upsert", "rule")
                call.respondCategoryDisabled("mocks")
                return@post
            }
            val parameters = call.receiveParameters()
            val id = parameters["id"].orEmpty()
            val priority = parameters["priority"]?.toIntOrNull()
            val status = parameters["status"]?.toIntOrNull()
            val scope =
                parameters["scope"]?.let { runCatching { MockScope.valueOf(it) }.getOrNull() } ?: MockScope.SESSION
            val delayMs = parameters["delayMs"]?.toLongOrNull()
            val headers = runCatching { parameters["headers"].parseMockRuleHeaders() }.getOrNull()
            val basicFieldsValid = id.matches(MOCK_RULE_ID) && priority != null && status in 100..599
            val delayValid = delayMs == null || delayMs in 0..MOCK_RULE_MAX_DELAY_MS
            if (!basicFieldsValid || !delayValid || headers == null) {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.upsert", id.ifBlank { "rule" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            val staticResponse = MockAction.StaticResponse(status!!, parameters["body"].orEmpty(), headers)
            val rule =
                MockRule(
                    id = id,
                    priority = priority,
                    method = parameters["method"]?.uppercase()?.takeIf { it.isNotBlank() },
                    scheme = parameters["scheme"]?.lowercase()?.takeIf { it.isNotBlank() },
                    host = parameters["host"]?.takeIf { it.isNotBlank() },
                    path = parameters["path"]?.takeIf { it.isNotBlank() } ?: ".*",
                    scope = scope,
                    action = if (delayMs != null) MockAction.Delay(delayMs, staticResponse) else staticResponse,
                    // Set only by the mock-from-transaction flows; hand-authored rules never send this
                    // field, and it is never written to disk (MockEngine strips it before persisting).
                    // The dashboard omits this param when editing a rule whose snapshot was reported
                    // truncated in the GET payload (a 64KB-capped, unparseable prefix -- see
                    // MockRule.json()/truncateForMockRuleJson), rather than round-tripping that prefix
                    // back as if it were the real snapshot. Falling back to the existing rule's own
                    // in-memory snapshot on an update (not the request param) is what makes that omission
                    // safe: a param-less save of a rule that already has a full snapshot keeps it intact,
                    // while a genuinely new/hand-written rule (no existing rule for this id) still
                    // defaults to null.
                    sourceBodySnapshot =
                        parameters["sourceBodySnapshot"]?.takeIf { it.isNotBlank() }
                            ?: mockEngine.rules().firstOrNull { it.id == id }?.sourceBodySnapshot,
                )
            runCatching { mockEngine.upsert(rule) }.onFailure {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.upsert", id)
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "mock.rule.upsert",
                id,
                mapOf(
                    "scope" to scope.name,
                    "status" to status.toString(),
                ),
            )
            call.respondText(
                "{\"id\":\"${id.escapeJson()}\",\"statusCode\":$status}",
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.Created,
            )
        }
        post("/api/v1/mocks/rules/{id}/enabled") {
            val id = call.parameters["id"].orEmpty()
            val session =
                call.mockRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    mocksEditable,
                    "mock.rule.enabled",
                    id.ifBlank { "rule" },
                ) ?: return@post
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.enabled", id.ifBlank { "rule" })
                call.respondCategoryDisabled("mocks")
                return@post
            }
            val enabled =
                call
                    .receiveText()
                    .trim()
                    .trim('"')
                    .toBooleanStrictOrNull()
            if (enabled == null || !runCatching { mockEngine.setEnabled(id, enabled) }.getOrDefault(false)) {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.enabled", id.ifBlank { "rule" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "mock.rule.enabled",
                id,
                mapOf(
                    "enabled" to enabled.toString(),
                ),
            )
            call.respondText("{\"status\":\"updated\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/mocks/rules/{id}") {
            val id = call.parameters["id"].orEmpty()
            val session =
                call.mockRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    mocksEditable,
                    "mock.rule.delete",
                    id.ifBlank { "rule" },
                ) ?: return@delete
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.delete", id.ifBlank { "rule" })
                call.respondCategoryDisabled("mocks")
                return@delete
            }
            if (!mockEngine.remove(id)) {
                commandAuditLog.recordControlFailure(session.id, "mock.rule.delete", id.ifBlank { "rule" })
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@delete
            }
            commandAuditLog.recordControlSuccess(session.id, "mock.rule.delete", id)
            call.respondText("{\"status\":\"deleted\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/flags") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) ==
                null
            ) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            // Feature flags live under the "state" capture category (state providers + feature flags),
            // so listing them is gated exactly like the state-provider routes.
            if (!categoryEnabled("state")) {
                call.respondCategoryDisabled("state")
                return@get
            }
            val flags =
                featureFlags.flags().joinToString(",") { flag ->
                    val allowed = flag.allowedValues.joinToString(",") { "\"${it.escapeJson()}\"" }
                    val value = featureFlags.value(flag.key)
                    "{\"key\":\"${flag.key.escapeJson()}\"," +
                        "\"description\":\"${flag.description.escapeJson()}\",\"type\":\"${flag.type.name}\"," +
                        "\"value\":\"${value.escapeJson()}\"," +
                        "\"defaultValue\":\"${flag.defaultValue.escapeJson()}\"," +
                        "\"source\":\"${flag.source.escapeJson()}\"," +
                        "\"mutable\":${flag.mutable},\"allowedValues\":[$allowed]}"
                }
            call.respondText("{\"data\":[$flags]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/flags/{key}") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            val key = call.parameters["key"].orEmpty()
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "flag.override", key.ifBlank { "flag" })
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            // Feature flags live under the "state" capture category; overriding one is gated by that
            // category and then by the featureFlags editing capability, mirroring the mock-rule routes.
            if (!categoryEnabled("state")) {
                commandAuditLog.recordControlFailure(session.id, "flag.override", key.ifBlank { "flag" })
                call.respondCategoryDisabled("state")
                return@post
            }
            if (!featureFlagsEditable) {
                commandAuditLog.recordControlFailure(session.id, "flag.override", key.ifBlank { "flag" })
                call.respondText("{\"code\":\"FEATURE_FLAGS_DISABLED\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            // Accepts a bare value, so "true" still works and "staging" now does too. Quotes are
            // tolerated because a browser posting JSON is the common case.
            val value = call.receiveText().trim().trim('"')
            val previousValue = featureFlags.value(key)
            val changed = runCatching { featureFlags.override(key, value) }.isSuccess
            if (!changed) {
                commandAuditLog.recordControlFailure(session.id, "flag.override", key.ifBlank { "flag" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            // Redact before/after through the engine keyed on the flag KEY as its field name: a flag
            // named like a secret stays masked, while an ordinary flag records its real prior and new value.
            val redactedBefore = redaction.redactFields(mapOf(key to previousValue)).getValue(key)
            val redactedAfter = redaction.redactFields(mapOf(key to featureFlags.value(key))).getValue(key)
            commandAuditLog.record(
                CommandAuditEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    browserSessionId = session.id,
                    commandType = "flag.override",
                    target = key,
                    result = CommandAuditResult.SUCCESS,
                    parameters =
                        mapOf(
                            "before" to redactedBefore,
                            "after" to redactedAfter,
                            "changed" to "true",
                        ),
                ),
            )
            timeline.appendFlagOverride(session.id, key, redactedBefore, redactedAfter)
            call.respondText("{\"status\":\"updated\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/capture-rules") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) ==
                null
            ) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("mocks")) {
                call.respondCategoryDisabled("mocks")
                return@get
            }
            val rules = captureRules.rules().joinToString(",") { it.json() }
            call.respondText(
                "{\"editable\":$captureRulesEditable,\"data\":[$rules]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/capture-rules") {
            val session =
                call.captureRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    captureRulesEditable,
                    "capture.rule.upsert",
                    "rule",
                )
                    ?: return@post
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "capture.rule.upsert", "rule")
                call.respondCategoryDisabled("mocks")
                return@post
            }
            val parameters = call.receiveParameters()
            val id = parameters["id"].orEmpty()
            val rule =
                runCatching {
                    CaptureRule.of(
                        id = id,
                        host = parameters["host"].orEmpty(),
                        method = parameters["method"],
                        pathPrefix = parameters["pathPrefix"],
                        enabled = parameters["enabled"]?.toBooleanStrictOrNull() ?: true,
                    )
                }.getOrNull()
            if (rule == null || runCatching { captureRules.upsert(rule) }.isFailure) {
                commandAuditLog.recordControlFailure(session.id, "capture.rule.upsert", id.ifBlank { "rule" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "capture.rule.upsert",
                rule.id,
                mapOf("host" to rule.host, "method" to (rule.method ?: "*"), "enabled" to rule.enabled.toString()),
            )
            call.respondText(
                rule.json(),
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.Created,
            )
        }
        post("/api/v1/capture-rules/{id}/enabled") {
            val id = call.parameters["id"].orEmpty()
            val session =
                call.captureRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    captureRulesEditable,
                    "capture.rule.enabled",
                    id.ifBlank { "rule" },
                ) ?: return@post
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "capture.rule.enabled", id.ifBlank { "rule" })
                call.respondCategoryDisabled("mocks")
                return@post
            }
            val enabled =
                call
                    .receiveText()
                    .trim()
                    .trim('"')
                    .toBooleanStrictOrNull()
            if (enabled == null || !runCatching { captureRules.setEnabled(id, enabled) }.getOrDefault(false)) {
                commandAuditLog.recordControlFailure(session.id, "capture.rule.enabled", id.ifBlank { "rule" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "capture.rule.enabled",
                id,
                mapOf(
                    "enabled" to enabled.toString(),
                ),
            )
            call.respondText("{\"status\":\"updated\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/capture-rules/{id}") {
            val id = call.parameters["id"].orEmpty()
            val session =
                call.captureRuleControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    captureRulesEditable,
                    "capture.rule.delete",
                    id.ifBlank { "rule" },
                ) ?: return@delete
            if (!categoryEnabled("mocks")) {
                commandAuditLog.recordControlFailure(session.id, "capture.rule.delete", id.ifBlank { "rule" })
                call.respondCategoryDisabled("mocks")
                return@delete
            }
            if (!runCatching { captureRules.remove(id) }.getOrDefault(false)) {
                commandAuditLog.recordControlFailure(session.id, "capture.rule.delete", id.ifBlank { "rule" })
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@delete
            }
            commandAuditLog.recordControlSuccess(session.id, "capture.rule.delete", id)
            call.respondText("{\"status\":\"deleted\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        // Evidence tray: durable, server-materialized QA items (see EvidenceStore's KDoc). Reads are
        // available to any authenticated session; every mutation is bearer+Origin+CSRF+audit like
        // every other command route. There is no separate editing capability -- unlike mocks/capture
        // rules/preferences/files/database, flagging evidence never lets a session touch host state,
        // so it is gated the same way bookmarks/notes are (see isReadMutationAuthorized).
        get("/api/v1/evidence") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val store = evidenceStore
            if (store == null) {
                call.respondText("{\"code\":\"EVIDENCE_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val sessionId = currentSessionId()
            val items = store.items(sessionId)
            val report = store.report(sessionId)
            // Bounded independently of the store's own quota (see MAX_EVIDENCE_ITEMS_PER_RESPONSE):
            // a single GET must never be able to serialize an unbounded number of snapshotted items,
            // even at this route's own 120 req/min ceiling. ?limit=/?offset= let a future caller page
            // through the rest; the shipped dashboard sends neither and reads only `data`/`report`, so
            // it sees every item it always has, unless a session is ever flagged past the cap.
            val limit =
                call.request.queryParameters["limit"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, MAX_EVIDENCE_ITEMS_PER_RESPONSE)
                    ?: MAX_EVIDENCE_ITEMS_PER_RESPONSE
            val offset =
                call.request.queryParameters["offset"]
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0
            val page = items.drop(offset).take(limit)
            val itemsJson = page.map { it.json(attachmentMetadataReader) }.joinToString(",")
            call.respondText(
                "{\"data\":[$itemsJson],\"report\":${report.json()}," +
                    "\"totalCount\":${items.size},\"hasMore\":${offset + page.size < items.size}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/evidence") {
            val session =
                call.evidenceControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    "evidence.flag",
                    "evidence",
                ) ?: return@post
            val store = evidenceStore
            if (store == null) {
                commandAuditLog.recordControlFailure(session.id, "evidence.flag", "evidence")
                call.respondText("{\"code\":\"EVIDENCE_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val parameters = call.receiveParameters()
            val kind =
                parameters["kind"]?.let { raw -> runCatching { EvidenceKind.valueOf(raw.uppercase()) }.getOrNull() }
            val subjectId = parameters["id"].orEmpty()
            if (kind == null || subjectId.isBlank() || subjectId.length > MAX_EVIDENCE_SUBJECT_ID_LENGTH) {
                commandAuditLog.recordControlFailure(session.id, "evidence.flag", subjectId.ifBlank { "evidence" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            val materialized =
                materializeEvidenceSubject(kind, subjectId, timeline, networkTransactions, socketStore, pushStore)
            if (materialized == null) {
                commandAuditLog.recordControlFailure(session.id, "evidence.flag", subjectId)
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@post
            }
            // Validated here, before the store is ever called: RoomEvidenceStore enforces the same
            // caps with require(), which throws IllegalArgumentException -- an over-long label must
            // come back as a clean VALIDATION_FAILED, not a 500 from the global exception boundary.
            if (materialized.label.length > MAX_EVIDENCE_LABEL_LENGTH) {
                commandAuditLog.recordControlFailure(session.id, "evidence.flag", subjectId)
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            val item =
                StoredEvidenceItem(
                    id = UUID.randomUUID().toString(),
                    sessionId = currentSessionId(),
                    kind = kind,
                    subjectId = subjectId,
                    label = materialized.label,
                    flaggedAtMs = System.currentTimeMillis(),
                    snapshotJson = materialized.snapshotJson,
                    attachmentId = materialized.attachmentId,
                )
            when (val result = store.flag(item)) {
                is EvidenceWriteResult.Success -> {
                    commandAuditLog.recordControlSuccess(
                        session.id,
                        "evidence.flag",
                        subjectId,
                        mapOf("kind" to kind.name),
                    )
                    call.respondText(
                        result.item.json(attachmentMetadataReader),
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.Created,
                    )
                }
                EvidenceWriteResult.AlreadyFlagged -> {
                    commandAuditLog.recordControlFailure(session.id, "evidence.flag", subjectId)
                    call.respondText("{\"code\":\"ALREADY_FLAGGED\"}", status = HttpStatusCode.Conflict)
                }
                EvidenceWriteResult.QuotaExceeded -> {
                    commandAuditLog.recordControlFailure(session.id, "evidence.flag", subjectId)
                    call.respondText("{\"code\":\"EVIDENCE_QUOTA_EXCEEDED\"}", status = HttpStatusCode.Conflict)
                }
                EvidenceWriteResult.Unavailable -> {
                    commandAuditLog.recordControlFailure(session.id, "evidence.flag", subjectId)
                    call.respondText("{\"code\":\"EVIDENCE_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                }
            }
        }
        delete("/api/v1/evidence/{kind}/{id}") {
            val subjectId = call.parameters["id"].orEmpty()
            val session =
                call.evidenceControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    "evidence.unflag",
                    subjectId.ifBlank { "evidence" },
                ) ?: return@delete
            val store = evidenceStore
            if (store == null) {
                commandAuditLog.recordControlFailure(session.id, "evidence.unflag", subjectId.ifBlank { "evidence" })
                call.respondText("{\"code\":\"EVIDENCE_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                return@delete
            }
            val kind =
                call.parameters["kind"]
                    ?.let { raw -> runCatching { EvidenceKind.valueOf(raw.uppercase()) }.getOrNull() }
            if (kind == null || subjectId.isBlank()) {
                commandAuditLog.recordControlFailure(session.id, "evidence.unflag", subjectId.ifBlank { "evidence" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@delete
            }
            store.unflag(currentSessionId(), kind, subjectId)
            commandAuditLog.recordControlSuccess(session.id, "evidence.unflag", subjectId)
            call.respondText("{\"status\":\"unflagged\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/evidence") {
            val session =
                call.evidenceControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    "evidence.clear",
                    "evidence",
                ) ?: return@delete
            val store = evidenceStore
            if (store == null) {
                commandAuditLog.recordControlFailure(session.id, "evidence.clear", "evidence")
                call.respondText("{\"code\":\"EVIDENCE_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                return@delete
            }
            store.clear(currentSessionId())
            commandAuditLog.recordControlSuccess(session.id, "evidence.clear", "evidence")
            call.respondText("{\"status\":\"cleared\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        put("/api/v1/evidence/report") {
            val session =
                call.evidenceControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    "evidence.report.save",
                    "evidence",
                ) ?: return@put
            val store = evidenceStore
            if (store == null) {
                commandAuditLog.recordControlFailure(session.id, "evidence.report.save", "evidence")
                call.respondText("{\"code\":\"EVIDENCE_UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                return@put
            }
            val parameters = call.receiveParameters()
            val severityRaw = parameters["severity"]
            val severity =
                if (severityRaw == null) {
                    EvidenceSeverity.MAJOR
                } else {
                    runCatching { EvidenceSeverity.valueOf(severityRaw.uppercase()) }.getOrNull()
                }
            val summary = parameters["summary"]
            val expected = parameters["expected"]
            val actual = parameters["actual"]
            val fieldsValid =
                (summary?.length ?: 0) <= MAX_EVIDENCE_TEXT_LENGTH &&
                    (expected?.length ?: 0) <= MAX_EVIDENCE_TEXT_LENGTH &&
                    (actual?.length ?: 0) <= MAX_EVIDENCE_TEXT_LENGTH
            if (severity == null || !fieldsValid) {
                commandAuditLog.recordControlFailure(session.id, "evidence.report.save", "evidence")
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@put
            }
            val report =
                StoredEvidenceReport(
                    sessionId = currentSessionId(),
                    severity = severity,
                    summary = summary,
                    expected = expected,
                    actual = actual,
                    updatedAtMs = System.currentTimeMillis(),
                )
            store.saveReport(report)
            commandAuditLog.recordControlSuccess(
                session.id,
                "evidence.report.save",
                "evidence",
                mapOf("severity" to severity.name),
            )
            call.respondText(report.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        // Off by default (ScreenshotPolicy.enabled = false): the most sensitive artifact this SDK can
        // emit. `screenshotCapture` already encodes that gate -- see DevConsoleFacadeProvider.captureScreenshot --
        // so this route only needs to map every ScreenshotResult variant to its own response.
        post("/api/v1/screenshots") {
            val session =
                call.evidenceControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    "screenshot.capture",
                    "screenshot",
                ) ?: return@post
            when (val result = screenshotCapture()) {
                is ScreenshotResult.Captured -> {
                    commandAuditLog.recordControlSuccess(
                        session.id,
                        "screenshot.capture",
                        result.attachmentId,
                        mapOf("widthPx" to result.widthPx.toString(), "heightPx" to result.heightPx.toString()),
                    )
                    call.respondText(
                        "{\"attachmentId\":\"${result.attachmentId.escapeJson()}\"," +
                            "\"eventId\":\"${result.eventId.escapeJson()}\"," +
                            "\"widthPx\":${result.widthPx},\"heightPx\":${result.heightPx}}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.Created,
                    )
                }
                ScreenshotResult.Disabled, ScreenshotResult.DisabledForBuild -> {
                    commandAuditLog.recordControlFailure(session.id, "screenshot.capture", "screenshot")
                    call.respondText("{\"code\":\"SCREENSHOT_DISABLED\"}", status = HttpStatusCode.Forbidden)
                }
                ScreenshotResult.NoForegroundActivity -> {
                    commandAuditLog.recordControlFailure(session.id, "screenshot.capture", "screenshot")
                    call.respondText("{\"code\":\"NO_FOREGROUND_ACTIVITY\"}", status = HttpStatusCode.Conflict)
                }
                ScreenshotResult.SecureWindow -> {
                    commandAuditLog.recordControlFailure(session.id, "screenshot.capture", "screenshot")
                    call.respondText("{\"code\":\"SECURE_WINDOW\"}", status = HttpStatusCode.Conflict)
                }
                is ScreenshotResult.Failed -> {
                    commandAuditLog.recordControlFailure(session.id, "screenshot.capture", "screenshot")
                    call.respondText("{\"code\":\"SCREENSHOT_FAILED\"}", status = HttpStatusCode.BadGateway)
                }
            }
        }
        get("/api/v1/preferences") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val files = preferencesInspector?.files().orEmpty().joinToString(",") { it.json() }
            call.respondText(
                "{\"editable\":$preferencesEditable,\"data\":[$files]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/preferences/{file}") {
            val file = call.parameters["file"].orEmpty()
            val session =
                call.preferencesControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    preferencesEditable,
                    "preferences.entry.set",
                    file,
                )
                    ?: return@post
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(session.id, "preferences.entry.set", file)
                call.respondCategoryDisabled("inspection")
                return@post
            }
            val inspector = preferencesInspector
            if (inspector == null) {
                commandAuditLog.recordControlFailure(session.id, "preferences.entry.set", file)
                call.respondText("{\"code\":\"UNAVAILABLE\"}", status = HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val parameters = call.receiveParameters()
            val key = parameters["key"].orEmpty()
            val value = parameters["value"].orEmpty()
            val type = parameters["type"].orEmpty()
            // The dashboard shows redacted entries as a placeholder, so accepting a write to one
            // would let a browser silently replace the real secret with that placeholder. The
            // device UI refuses to edit redacted entries; the server must match it.
            val redactedEntry =
                inspector
                    .files()
                    .firstOrNull { it.name == file }
                    ?.entries
                    ?.firstOrNull { it.key == key }
                    ?.redacted == true
            if (redactedEntry) {
                commandAuditLog.recordControlFailure(session.id, "preferences.entry.set", "$file:$key")
                call.respondText("{\"code\":\"REDACTED_WRITE_BLOCKED\"}", status = HttpStatusCode.Conflict)
                return@post
            }
            if (key.isBlank() || type.isBlank() || !inspector.put(file, key, value, type)) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "preferences.entry.set",
                    "$file:${key.ifBlank { "key" }}",
                )
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "preferences.entry.set",
                "$file:$key",
                mapOf("type" to type),
            )
            call.respondText("{\"status\":\"updated\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/preferences/{file}") {
            val file = call.parameters["file"].orEmpty()
            val key = call.request.queryParameters["key"].orEmpty()
            val session =
                call.preferencesControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    preferencesEditable,
                    "preferences.entry.remove",
                    "$file:${key.ifBlank { "key" }}",
                ) ?: return@delete
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "preferences.entry.remove",
                    "$file:${key.ifBlank { "key" }}",
                )
                call.respondCategoryDisabled("inspection")
                return@delete
            }
            val inspector = preferencesInspector
            if (key.isBlank() || inspector == null || !inspector.remove(file, key)) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "preferences.entry.remove",
                    "$file:${key.ifBlank { "key" }}",
                )
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@delete
            }
            commandAuditLog.recordControlSuccess(session.id, "preferences.entry.remove", "$file:$key")
            call.respondText("{\"status\":\"deleted\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/database") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val names =
                withContext(Dispatchers.IO) { databaseInspector?.databases() }
                    .orEmpty()
                    .joinToString(",") { "\"${it.escapeJson()}\"" }
            call.respondText(
                "{\"editable\":$databaseEditable,\"data\":[$names]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/database/{name}") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val listing = withContext(Dispatchers.IO) { databaseInspector?.tables(call.parameters["name"].orEmpty()) }
            if (listing == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(listing.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/database/{name}/tables/{table}") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val query =
                withContext(Dispatchers.IO) {
                    databaseInspector?.query(call.parameters["name"].orEmpty(), call.parameters["table"].orEmpty())
                }
            if (query == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(query.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/database/{name}/sql") {
            val database = call.parameters["name"].orEmpty()
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "database.sql.execute", database)
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(session.id, "database.sql.execute", database)
                call.respondCategoryDisabled("inspection")
                return@post
            }
            // The capability gates SELECT too: redaction keys on result-set column names, so a
            // caller who writes the SQL can alias any column past it. Enabling this capability is
            // enabling raw database access, and the route must not be softer than that.
            if (!databaseEditable) {
                commandAuditLog.recordControlFailure(session.id, "database.sql.execute", database)
                call.respondText("{\"code\":\"DATABASE_DISABLED\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val inspector = databaseInspector
            val sql = call.receiveParameters()["sql"].orEmpty()
            if (inspector == null || sql.isBlank() || sql.length > MAX_SQL_INPUT_BYTES) {
                commandAuditLog.recordControlFailure(session.id, "database.sql.execute", database)
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            val result =
                withContext(Dispatchers.IO) {
                    inspector.execute(database, sql, writeEnabled = databaseEditable)
                }
            when (result) {
                is DatabaseExecResult.Query -> {
                    commandAuditLog.recordControlSuccess(
                        session.id,
                        "database.sql.execute",
                        database,
                        mapOf(
                            "kind" to "QUERY",
                        ),
                    )
                    call.respondText(
                        "{\"kind\":\"QUERY\",${result.result.jsonFields()}}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                }
                is DatabaseExecResult.Write -> {
                    commandAuditLog.recordControlSuccess(
                        session.id,
                        "database.sql.execute",
                        database,
                        mapOf(
                            "kind" to "WRITE",
                        ),
                    )
                    call.respondText(
                        "{\"kind\":\"WRITE\",\"affectedRows\":${result.affectedRows}}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                }
                DatabaseExecResult.WriteBlocked -> {
                    commandAuditLog.recordControlFailure(session.id, "database.sql.execute", database)
                    call.respondText("{\"code\":\"DATABASE_WRITE_BLOCKED\"}", status = HttpStatusCode.Forbidden)
                }
                is DatabaseExecResult.Failed -> {
                    commandAuditLog.recordControlFailure(session.id, "database.sql.execute", database)
                    call.respondText(
                        "{\"code\":\"VALIDATION_FAILED\",\"message\":\"${result.message.escapeJson()}\"}",
                        status = HttpStatusCode.BadRequest,
                    )
                }
            }
        }
        get("/api/v1/files") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val roots =
                withContext(Dispatchers.IO) { fileInspector?.roots() }
                    .orEmpty()
                    .joinToString(",") { "\"${it.escapeJson()}\"" }
            call.respondText(
                "{\"editable\":$filesEditable,\"data\":[$roots]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/files/{root}") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val relativePath = call.request.queryParameters["path"].orEmpty()
            val listing =
                withContext(Dispatchers.IO) { fileInspector?.list(call.parameters["root"].orEmpty(), relativePath) }
            if (listing == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(listing.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/files/{root}/preview") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val relativePath = call.request.queryParameters["path"].orEmpty()
            val preview =
                withContext(Dispatchers.IO) { fileInspector?.preview(call.parameters["root"].orEmpty(), relativePath) }
                    ?: FilePreviewData.Unavailable("Inspector is not connected")
            call.respondText(preview.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/files/{root}") {
            val root = call.parameters["root"].orEmpty()
            val relativePath = call.request.queryParameters["path"].orEmpty()
            val session =
                call.filesControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    filesEditable,
                    "files.delete",
                    "$root:${relativePath.ifBlank { "/" }}",
                ) ?: return@delete
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "files.delete",
                    "$root:${relativePath.ifBlank { "/" }}",
                )
                call.respondCategoryDisabled("inspection")
                return@delete
            }
            val inspector = fileInspector
            val deleted =
                relativePath.isNotBlank() &&
                    inspector != null &&
                    withContext(Dispatchers.IO) { inspector.delete(root, relativePath) }
            if (!deleted) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "files.delete",
                    "$root:${relativePath.ifBlank { "/" }}",
                )
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@delete
            }
            commandAuditLog.recordControlSuccess(session.id, "files.delete", "$root:$relativePath")
            call.respondText("{\"status\":\"deleted\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        put("/api/v1/files/{root}") {
            val root = call.parameters["root"].orEmpty()
            // The gate runs before the body is ever read: an unauthenticated/CSRF-invalid/disabled
            // request must not have to send a well-formed form body to be rejected, and reading the
            // body first would answer 415 for a request that omits Content-Type entirely (see the
            // read-only-denied test) instead of the intended 401/403.
            val session =
                call.filesControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    filesEditable,
                    "files.create",
                    root,
                ) ?: return@put
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(session.id, "files.create", root)
                call.respondCategoryDisabled("inspection")
                return@put
            }
            val parameters = call.receiveParameters()
            val relativePath = parameters["path"].orEmpty()
            val content = parameters["content"].orEmpty()
            val inspector = fileInspector
            val blankTarget = "$root:${relativePath.ifBlank { "/" }}"
            if (relativePath.isBlank() || content.length > MAX_FILE_WRITE_INPUT_BYTES || inspector == null) {
                commandAuditLog.recordControlFailure(session.id, "files.create", blankTarget)
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@put
            }
            if (!withContext(Dispatchers.IO) { inspector.create(root, relativePath, content) }) {
                commandAuditLog.recordControlFailure(session.id, "files.create", "$root:$relativePath")
                call.respondText("{\"code\":\"CONFLICT\"}", status = HttpStatusCode.Conflict)
                return@put
            }
            commandAuditLog.recordControlSuccess(session.id, "files.create", "$root:$relativePath")
            call.respondText("{\"status\":\"created\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/files/{root}/rename") {
            val root = call.parameters["root"].orEmpty()
            val session =
                call.filesControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    filesEditable,
                    "files.rename",
                    root,
                ) ?: return@post
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(session.id, "files.rename", root)
                call.respondCategoryDisabled("inspection")
                return@post
            }
            val parameters = call.receiveParameters()
            val relativePath = parameters["path"].orEmpty()
            val newRelativePath = parameters["newPath"].orEmpty()
            val inspector = fileInspector
            val blankTarget = "$root:${relativePath.ifBlank { "/" }}"
            if (relativePath.isBlank() || newRelativePath.isBlank() || inspector == null) {
                commandAuditLog.recordControlFailure(session.id, "files.rename", blankTarget)
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (!withContext(Dispatchers.IO) { inspector.rename(root, relativePath, newRelativePath) }) {
                commandAuditLog.recordControlFailure(session.id, "files.rename", "$root:$relativePath")
                call.respondText("{\"code\":\"CONFLICT\"}", status = HttpStatusCode.Conflict)
                return@post
            }
            commandAuditLog.recordControlSuccess(session.id, "files.rename", "$root:$relativePath->$newRelativePath")
            call.respondText("{\"status\":\"renamed\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/files/{root}") {
            val root = call.parameters["root"].orEmpty()
            val session =
                call.filesControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    filesEditable,
                    "files.replace",
                    root,
                ) ?: return@post
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(session.id, "files.replace", root)
                call.respondCategoryDisabled("inspection")
                return@post
            }
            val parameters = call.receiveParameters()
            val relativePath = parameters["path"].orEmpty()
            val content = parameters["content"].orEmpty()
            val inspector = fileInspector
            val blankTarget = "$root:${relativePath.ifBlank { "/" }}"
            if (relativePath.isBlank() || content.length > MAX_FILE_WRITE_INPUT_BYTES || inspector == null) {
                commandAuditLog.recordControlFailure(session.id, "files.replace", blankTarget)
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (!withContext(Dispatchers.IO) { inspector.replace(root, relativePath, content) }) {
                commandAuditLog.recordControlFailure(session.id, "files.replace", "$root:$relativePath")
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@post
            }
            commandAuditLog.recordControlSuccess(session.id, "files.replace", "$root:$relativePath")
            call.respondText("{\"status\":\"updated\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/files/{root}/download") {
            // Preview redacts text content, but a download streams raw, unredacted bytes -- the same
            // reasoning as the SQL console above (`database.sql.execute`): an authenticated session
            // plus the `files` capability, exactly like every other route that reaches unredacted
            // data, even though this request is a side-effect-free GET.
            val root = call.parameters["root"].orEmpty()
            val relativePath = call.request.queryParameters["path"].orEmpty()
            val target = "$root:${relativePath.ifBlank { "/" }}"
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!filesEditable) {
                commandAuditLog.recordControlFailure(session.id, "files.download", target)
                call.respondText("{\"code\":\"FILES_DISABLED\"}", status = HttpStatusCode.Forbidden)
                return@get
            }
            if (!categoryEnabled("inspection")) {
                commandAuditLog.recordControlFailure(session.id, "files.download", target)
                call.respondCategoryDisabled("inspection")
                return@get
            }
            val bytes = withContext(Dispatchers.IO) { fileInspector?.readBytes(root, relativePath) }
            if (relativePath.isBlank() || bytes == null) {
                commandAuditLog.recordControlFailure(session.id, "files.download", target)
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "files.download",
                target,
                mapOf("bytes" to bytes.size.toString()),
            )
            val filename = relativePath.substringAfterLast('/').sanitizeFilenameForHeader()
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
            call.respondBytes(bytes, contentType = ContentType.Application.OctetStream)
        }
        get("/api/v1/attachments/{id}") {
            // attachmentId already rides along on every transaction/event JSON payload; unlike file
            // downloads, these bytes were already bounded and redacted at capture time (see
            // EventExportWriter's attachment handling), so this needs no extra editing capability --
            // just the same authenticated session every other read route requires.
            val id = call.parameters["id"].orEmpty()
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val bytes =
                if (id.isBlank()) {
                    null
                } else {
                    try {
                        attachmentReader(id)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            if (bytes == null) {
                commandAuditLog.recordControlFailure(session.id, "attachments.download", id.ifBlank { "attachment" })
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "attachments.download",
                id,
                mapOf("bytes" to bytes.size.toString()),
            )
            val filename = id.sanitizeFilenameForHeader()
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
            // Authoritative from stored data, never inferred: a body is the response here, so this
            // rides as a header rather than a JSON field -- the one place in this route a client can
            // read StoredAttachment.redactionApplicability without a second request. Omitted (not
            // defaulted to APPLIED) when the reader is unwired or the row is gone, so a client can
            // tell "unredacted" apart from "unknown".
            val applicability =
                try {
                    attachmentMetadataReader(id)?.redactionApplicability
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            applicability?.let {
                call.response.headers.append("X-DevConsole-Redaction-Applicability", it.name)
            }
            call.respondBytes(bytes, contentType = ContentType.Application.OctetStream)
        }
        post("/api/v1/composer/execute") {
            // Same shared gate as Network resend (composerExecutionControlSession). The
            // composerEnabled branch inside it is unreachable here -- the /api/v1/composer prefix
            // intercept already 404s pre-auth when the feature is off -- but sharing the helper
            // keeps the auth/CSRF chain one structure instead of a hand-kept copy.
            val session =
                call.composerExecutionControlSession(
                    sessionAuthority,
                    commandAuditLog,
                    composerEnabled = true,
                    commandType = "composer.execute",
                    target = "request",
                ) ?: return@post
            val parameters = call.receiveParameters()
            val request = parameters.toComposerRequestOrNull()
            val method = request?.method.orEmpty()
            val url = request?.url.orEmpty()
            if (request == null || method !in COMPOSER_METHODS || !url.isHttpUrl()) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "composer.execute",
                    url.hostOrEmpty().ifBlank { "request" },
                )
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (!composerAllowedHosts.permitsHostOf(url)) {
                commandAuditLog.recordControlFailure(session.id, "composer.execute", url.hostOrEmpty())
                call.respondText("{\"code\":\"COMPOSER_HOST_REJECTED\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val correlationId = UUID.randomUUID().toString()
            val startedAtEpochMs = System.currentTimeMillis()
            val resolvedRequest =
                runCatching { request.resolve() }.getOrElse {
                    commandAuditLog.recordControlFailure(session.id, "composer.execute", url.hostOrEmpty())
                    call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                    return@post
                }
            val execution =
                try {
                    composerExecutor.execute(
                        request,
                        composerAllowedHosts::permitsHostOf,
                    )
                } catch (rejected: io.devconsole.composer.ComposerDestinationRejectedException) {
                    recordComposerCapture(
                        request = resolvedRequest,
                        secretValues = request.secretValues(),
                        response = null,
                        error = rejected,
                        correlationId = correlationId,
                        browserSessionId = session.id,
                        startedAtEpochMs = startedAtEpochMs,
                        networkTransactions = networkTransactions,
                        timeline = timeline,
                        redaction = redaction,
                    )
                    commandAuditLog.recordControlFailure(
                        session.id,
                        "composer.execute",
                        rejected.destination.hostOrEmpty(),
                    )
                    call.respondText("{\"code\":\"COMPOSER_HOST_REJECTED\"}", status = HttpStatusCode.Forbidden)
                    return@post
                } catch (failure: Throwable) {
                    recordComposerCapture(
                        request = resolvedRequest,
                        secretValues = request.secretValues(),
                        response = null,
                        error = failure,
                        correlationId = correlationId,
                        browserSessionId = session.id,
                        startedAtEpochMs = startedAtEpochMs,
                        networkTransactions = networkTransactions,
                        timeline = timeline,
                        redaction = redaction,
                    )
                    commandAuditLog.recordControlFailure(session.id, "composer.execute", url.hostOrEmpty())
                    call.respondText("{\"code\":\"EXECUTION_FAILED\"}", status = HttpStatusCode.BadGateway)
                    return@post
                }
            recordComposerCapture(
                request = resolvedRequest,
                secretValues = request.secretValues(),
                response = execution.response,
                error = null,
                correlationId = correlationId,
                browserSessionId = session.id,
                startedAtEpochMs = startedAtEpochMs,
                networkTransactions = networkTransactions,
                timeline = timeline,
                redaction = redaction,
            )
            // Query strings routinely carry credentials, and the audit log is readable over HTTP.
            commandAuditLog.recordControlSuccess(
                session.id,
                "composer.execute",
                method,
                mapOf("url" to url.withoutQuery()),
            )
            call.respondText(
                "{\"correlationId\":\"${correlationId.escapeJson()}\",\"request\":${execution.requestMetadata}," +
                    "\"response\":{\"statusCode\":${execution.response.statusCode}," +
                    "\"durationMs\":${execution.response.durationMs ?: "null"}}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/composer/collections") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val collections =
                composerCollections.collections().joinToString(",") { collection ->
                    val requestMetadata = collection.request.toRedactedMetadata(redaction)
                    "{\"id\":\"${collection.id.escapeJson()}\"," +
                        "\"name\":\"${collection.name.escapeJson()}\",\"request\":$requestMetadata}"
                }
            call.respondText("{\"data\":[$collections]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/composer/import") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "composer.import", "curl")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val command = call.receiveText()
            val request = ComposerCurlImporter.import(command).getOrNull()
            if (request == null) {
                commandAuditLog.recordControlFailure(session.id, "composer.import", "curl")
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            commandAuditLog.recordControlSuccess(
                session.id,
                "composer.import",
                request.method,
                mapOf(
                    "url" to request.url,
                ),
            )
            call.respondText(
                request.toRedactedMetadata(redaction),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/composer/collections") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "composer.collection.save", "collection")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val parameters = call.receiveParameters()
            val request = ComposerCurlImporter.import(parameters["curl"].orEmpty()).getOrNull()
            val name = parameters["name"].orEmpty()
            if (request == null || name.isBlank()) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "composer.collection.save",
                    name.ifBlank { "collection" },
                )
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            val collection = composerCollections.save(name, request)
            commandAuditLog.recordControlSuccess(
                session.id,
                "composer.collection.save",
                collection.id,
                mapOf(
                    "name" to collection.name,
                ),
            )
            val requestMetadata = collection.request.toRedactedMetadata(redaction)
            call.respondText(
                "{\"id\":\"${collection.id.escapeJson()}\"," +
                    "\"name\":\"${collection.name.escapeJson()}\",\"request\":$requestMetadata}",
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.Created,
            )
        }
        delete("/api/v1/composer/collections/{id}") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@delete
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "composer.collection.delete",
                    call.parameters["id"].orEmpty().ifBlank { "collection" },
                )
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@delete
            }
            val id = call.parameters["id"].orEmpty()
            if (!composerCollections.remove(id)) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "composer.collection.delete",
                    id.ifBlank { "collection" },
                )
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@delete
            }
            commandAuditLog.recordControlSuccess(session.id, "composer.collection.delete", id)
            call.respondText("{\"status\":\"deleted\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/state") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("state")) {
                call.respondCategoryDisabled("state")
                return@get
            }
            val providers =
                stateRegistry.providerIds().joinToString(",") { id ->
                    "{\"id\":\"${id.escapeJson()}\",\"mutators\":${stateRegistry.mutators(id).json()}}"
                }
            call.respondText("{\"data\":[$providers]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/push/events") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("push")) {
                call.respondCategoryDisabled("push")
                return@get
            }
            val events = pushStore.events().joinToString(",") { it.json() }
            call.respondText("{\"data\":[$events]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/push/simulate") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(session.id, "push.simulate", "push")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (!categoryEnabled("push")) {
                commandAuditLog.recordControlFailure(session.id, "push.simulate", "push")
                call.respondCategoryDisabled("push")
                return@post
            }
            val simulator = pushSimulator
            if (simulator == null) {
                commandAuditLog.recordControlFailure(session.id, "push.simulate", "push")
                call.respondText("{\"code\":\"PUSH_SIMULATION_UNAVAILABLE\"}", status = HttpStatusCode.Conflict)
                return@post
            }
            val parameters = call.receiveParameters()
            val data =
                parameters.names().filter { it.startsWith("data.") }.associate { name ->
                    name.removePrefix("data.") to
                        parameters[name].orEmpty()
                }
            val event =
                simulator.simulate(
                    PushInput(
                        provider = parameters["provider"].orEmpty(),
                        messageId = parameters["messageId"],
                        source = "local-simulation",
                        data = data,
                    ),
                )
            commandAuditLog.recordControlSuccess(
                session.id,
                "push.simulate",
                event.messageId ?: event.provider,
                mapOf(
                    "provider" to event.provider,
                ),
            )
            call.respondText(event.json(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        // Historical path: the command audit predates the plugin framework's removal and browsers
        // already consume it here; renaming the route is a protocol break for no gain.
        get("/api/v1/plugins/audit") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val events =
                commandAuditLog.events().joinToString(",") { event ->
                    val params =
                        event.parameters.entries.joinToString(",") { (k, v) ->
                            "\"${k.escapeJson()}\":\"${v.escapeJson()}\""
                        }
                    "{\"timestampEpochMs\":${event.timestampEpochMs}," +
                        "\"browserSessionId\":\"${event.browserSessionId.escapeJson()}\"," +
                        "\"commandType\":\"${event.commandType.escapeJson()}\"," +
                        "\"target\":\"${event.target.escapeJson()}\"," +
                        "\"result\":\"${event.result.name}\",\"parameters\":{$params}}"
                }
            call.respondText("{\"data\":[$events]}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/state/{id}") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("state")) {
                call.respondCategoryDisabled("state")
                return@get
            }
            val id = call.parameters["id"].orEmpty()
            val snapshot = stateRegistry.snapshot(id)
            if (snapshot == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(
                "{\"id\":\"${id.escapeJson()}\",\"values\":${snapshot.json()}," +
                    "\"mutators\":${stateRegistry.mutators(id).json()}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/state/{id}/mutations/{command}") {
            if (!stateMutationsEnabled) {
                sessionAuthority
                    .bearerSession(call.request.headers[HttpHeaders.Authorization])
                    ?.let { session ->
                        commandAuditLog.recordControlFailure(
                            session.id,
                            "state.mutation",
                            "${call.parameters["id"]}.${call.parameters["command"]}",
                        )
                    }
                call.respondText("{\"code\":\"STATE_MUTATION_DISABLED\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin ||
                call.request.headers[CSRF_HEADER] != session.csrfToken
            ) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "state.mutation",
                    "${call.parameters["id"]}.${call.parameters["command"]}",
                )
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (!categoryEnabled("state")) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "state.mutation",
                    "${call.parameters["id"]}.${call.parameters["command"]}",
                )
                call.respondCategoryDisabled("state")
                return@post
            }
            val input = call.receiveText()
            if (input.length > MAX_STATE_MUTATION_INPUT_BYTES) {
                commandAuditLog.recordControlFailure(
                    session.id,
                    "state.mutation",
                    "${call.parameters["id"]}.${call.parameters["command"]}",
                )
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            val providerId = call.parameters["id"].orEmpty()
            val commandId = call.parameters["command"].orEmpty()
            when (val result = stateRegistry.mutate(providerId, commandId, input)) {
                null -> {
                    commandAuditLog.recordControlFailure(session.id, "state.mutation", "$providerId.$commandId")
                    call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                }
                is StateMutationResult.Rejected -> {
                    commandAuditLog.recordControlFailure(session.id, "state.mutation", "$providerId.$commandId")
                    call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                }
                is StateMutationResult.Success -> {
                    commandAuditLog.recordControlSuccess(session.id, "state.mutation", "$providerId.$commandId")
                    call.respondText(
                        "{\"id\":\"${providerId.escapeJson()}\",\"values\":${result.snapshot.json()}}",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                }
            }
        }
        get("/api/v1/websockets/connections") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("socket") && !categoryEnabled("mqtt")) {
                call.respondCategoryDisabled("socket")
                return@get
            }
            val protocolFilter = call.socketProtocolFilter()
            val connections =
                socketStore
                    .connections()
                    .filter { protocolFilter == null || it.protocol == protocolFilter }
                    .joinToString(",") { it.summaryJson() }
            call.respondText(
                "{\"schemaVersion\":1,\"data\":[$connections]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/websockets/connections/{id}") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("socket") && !categoryEnabled("mqtt")) {
                call.respondCategoryDisabled("socket")
                return@get
            }
            val connection = socketStore.connection(call.parameters["id"].orEmpty())
            if (connection == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(
                "{\"schemaVersion\":1,\"data\":${connection.detailJson()}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/websockets/messages") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("socket") && !categoryEnabled("mqtt")) {
                call.respondCategoryDisabled("socket")
                return@get
            }
            val query = call.socketMessageQuery()
            if (query == null) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@get
            }
            val protocolFilter = call.socketProtocolFilter()
            val messages =
                socketStore
                    .messages(query)
                    .filter { message ->
                        protocolFilter == null ||
                            socketStore.connection(message.connectionId)?.protocol == protocolFilter
                    }.joinToString(",") { it.json() }
            call.respondText(
                "{\"schemaVersion\":1,\"data\":[$messages]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/network/transactions/{id}") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@get
            }
            val transaction = networkTransactions.find(call.parameters["id"].orEmpty())
            if (transaction == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(transaction.detailJson(), contentType = io.ktor.http.ContentType.Application.Json)
        }
        get("/api/v1/network/transactions/{id}/related-events") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@get
            }
            val transaction = networkTransactions.find(call.parameters["id"].orEmpty())
            if (transaction == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            val rawWindowMs = call.request.queryParameters["windowMs"]
            val windowMs =
                rawWindowMs?.toLongOrNull()
                    ?: if (rawWindowMs == null) {
                        DEFAULT_RELATED_EVENT_WINDOW_MS
                    } else {
                        call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                        return@get
                    }
            if (windowMs !in 0..MAX_RELATED_EVENT_WINDOW_MS) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@get
            }
            val fromEpochMs =
                if (transaction.startedAtEpochMs < windowMs) {
                    0
                } else {
                    transaction.startedAtEpochMs - windowMs
                }
            val completedAtEpochMs = transaction.completedAtEpochMs ?: transaction.startedAtEpochMs
            val toEpochMs =
                if (Long.MAX_VALUE - completedAtEpochMs < windowMs) {
                    Long.MAX_VALUE
                } else {
                    completedAtEpochMs + windowMs
                }
            val byTime =
                (
                    timeline.page(
                        TimelineQuery(limit = TimelineQuery.MAX_PAGE_LIMIT)
                            .withTimeRange(fromEpochMs, toEpochMs),
                    ) as? TimelinePage.Success
                )?.events.orEmpty()
            val byCorrelation =
                transaction.capture.request.correlationId
                    ?.let { correlationId ->
                        (
                            timeline.page(
                                TimelineQuery(
                                    limit = TimelineQuery.MAX_PAGE_LIMIT,
                                    correlationId = correlationId,
                                ),
                            ) as? TimelinePage.Success
                        )?.events.orEmpty()
                    }.orEmpty()
            val events =
                (byTime + byCorrelation)
                    .distinctBy(StoredEvent::id)
                    .sortedWith(
                        compareBy<StoredEvent> { it.monoTimeNs }
                            .thenBy { it.sequence }
                            .thenBy { it.id },
                    ).take(TimelineQuery.MAX_PAGE_LIMIT)
            call.respondText(
                "{\"data\":[${events.joinToString(",") { it.relatedEventJson() }}]}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/network/transactions/{id}/curl") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@get
            }
            val transaction = networkTransactions.find(call.parameters["id"].orEmpty())
            if (transaction == null) {
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(
                NetworkExport.toCurl(transaction.capture, redaction),
                contentType = io.ktor.http.ContentType.Text.Plain,
            )
        }
        // Re-executes a captured request through the same SDK-owned composer transport the Composer
        // route uses -- never a browser fetch -- with identical gating (capability, allowlist,
        // CSRF/origin, rate limit) and identical failure codes, so a one-click "Resend" carries the
        // exact same guarantees a full Composer round trip would. The re-issued call is captured and
        // timeline-appended via the same [recordComposerCapture] helper Composer execution uses, so it
        // shows up in Network/Timeline (source=composer) identically to any other composer execution.
        post("/api/v1/network/transactions/{id}/resend") {
            val transactionId = call.parameters["id"].orEmpty()
            val session =
                call.composerExecutionControlSession(
                    sessionAuthority = sessionAuthority,
                    commandAuditLog = commandAuditLog,
                    composerEnabled = composerEnabled,
                    commandType = "network.resend",
                    target = transactionId.ifBlank { "transaction" },
                ) ?: return@post
            if (!categoryEnabled("network")) {
                commandAuditLog
                    .recordControlFailure(session.id, "network.resend", transactionId.ifBlank { "transaction" })
                call.respondCategoryDisabled("network")
                return@post
            }
            val transaction = networkTransactions.find(transactionId)
            if (transaction == null) {
                commandAuditLog
                    .recordControlFailure(session.id, "network.resend", transactionId.ifBlank { "transaction" })
                call.respondText("{\"code\":\"NOT_FOUND\"}", status = HttpStatusCode.NotFound)
                return@post
            }
            val captured = transaction.capture.request
            val url = captured.url.display
            if (captured.method !in COMPOSER_METHODS || !url.isHttpUrl()) {
                commandAuditLog
                    .recordControlFailure(session.id, "network.resend", url.hostOrEmpty().ifBlank { "request" })
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (!composerAllowedHosts.permitsHostOf(url)) {
                commandAuditLog.recordControlFailure(session.id, "network.resend", url.hostOrEmpty())
                call.respondText("{\"code\":\"COMPOSER_HOST_REJECTED\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            val request =
                ComposerRequest(
                    method = captured.method,
                    url = url,
                    headers = captured.headers,
                    body = (captured.body as? BodyPreview.Text)?.value,
                    bodyType = if (captured.body is BodyPreview.Text) ComposerBodyType.TEXT else ComposerBodyType.NONE,
                )
            val correlationId = UUID.randomUUID().toString()
            val startedAtEpochMs = System.currentTimeMillis()
            val resolvedRequest =
                runCatching { request.resolve() }.getOrElse {
                    commandAuditLog.recordControlFailure(session.id, "network.resend", url.hostOrEmpty())
                    call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                    return@post
                }
            val execution =
                try {
                    composerExecutor.execute(
                        request,
                        composerAllowedHosts::permitsHostOf,
                    )
                } catch (rejected: io.devconsole.composer.ComposerDestinationRejectedException) {
                    recordComposerCapture(
                        request = resolvedRequest,
                        secretValues = request.secretValues(),
                        response = null,
                        error = rejected,
                        correlationId = correlationId,
                        browserSessionId = session.id,
                        startedAtEpochMs = startedAtEpochMs,
                        networkTransactions = networkTransactions,
                        timeline = timeline,
                        redaction = redaction,
                    )
                    commandAuditLog.recordControlFailure(
                        session.id,
                        "network.resend",
                        rejected.destination.hostOrEmpty(),
                    )
                    call.respondText("{\"code\":\"COMPOSER_HOST_REJECTED\"}", status = HttpStatusCode.Forbidden)
                    return@post
                } catch (failure: Throwable) {
                    recordComposerCapture(
                        request = resolvedRequest,
                        secretValues = request.secretValues(),
                        response = null,
                        error = failure,
                        correlationId = correlationId,
                        browserSessionId = session.id,
                        startedAtEpochMs = startedAtEpochMs,
                        networkTransactions = networkTransactions,
                        timeline = timeline,
                        redaction = redaction,
                    )
                    commandAuditLog.recordControlFailure(session.id, "network.resend", url.hostOrEmpty())
                    call.respondText("{\"code\":\"EXECUTION_FAILED\"}", status = HttpStatusCode.BadGateway)
                    return@post
                }
            recordComposerCapture(
                request = resolvedRequest,
                secretValues = request.secretValues(),
                response = execution.response,
                error = null,
                correlationId = correlationId,
                browserSessionId = session.id,
                startedAtEpochMs = startedAtEpochMs,
                networkTransactions = networkTransactions,
                timeline = timeline,
                redaction = redaction,
            )
            commandAuditLog.recordControlSuccess(
                session.id,
                "network.resend",
                captured.method,
                mapOf("url" to url.withoutQuery()),
            )
            call.respondText(
                "{\"correlationId\":\"${correlationId.escapeJson()}\",\"request\":${execution.requestMetadata}," +
                    "\"response\":{\"statusCode\":${execution.response.statusCode}," +
                    "\"durationMs\":${execution.response.durationMs ?: "null"}}}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        get("/api/v1/network/har") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@get
            }
            val resolution = call.resolveNetworkExportTransactions(networkTransactions) ?: return@get
            call.applyExportTruncationHeaders(resolution)
            call.respondText(
                NetworkExport.toHarTransactions(resolution.transactions, redaction),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        // Same [ExportSelection] resolution and auth gate as /api/v1/network/har above -- the two
        // formats must never disagree about which rows a given `id`/filter query selects.
        get("/api/v1/network/postman") {
            if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@get
            }
            val resolution = call.resolveNetworkExportTransactions(networkTransactions) ?: return@get
            call.applyExportTruncationHeaders(resolution)
            call.respondText(
                NetworkExport.toPostman(resolution.transactions, redaction),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        // POST siblings of the two GET routes above, for a checkbox-driven selection wide enough
        // that its ids would not safely fit a GET query string (browsers/proxies commonly cap a
        // request line around 4-8KB; MAX_PAGE_LIMIT ids at UUID length alone can exceed that). Ids
        // travel in the form body instead of the query string; every other filter (method/host/
        // status/search/...) still comes off the query string, same as the GET routes, so "select
        // all matching the current filter" -- which never needs a long id list -- keeps working
        // unchanged through either verb. Gated like the other mutation-shaped export route
        // (`POST /api/v1/exports`): bearer session plus Origin+CSRF, not just bearer alone, since a
        // POST is what a same-origin-restricted browser would otherwise auto-CSRF.
        post("/api/v1/network/har") {
            val resolution =
                call.authorizeNetworkExportPost(networkTransactions, sessionAuthority, commandAuditLog)
                    ?: return@post
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@post
            }
            call.applyExportTruncationHeaders(resolution)
            call.respondText(
                NetworkExport.toHarTransactions(resolution.transactions, redaction),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/network/postman") {
            val resolution =
                call.authorizeNetworkExportPost(networkTransactions, sessionAuthority, commandAuditLog)
                    ?: return@post
            if (!categoryEnabled("network")) {
                call.respondCategoryDisabled("network")
                return@post
            }
            call.applyExportTruncationHeaders(resolution)
            call.respondText(
                NetworkExport.toPostman(resolution.transactions, redaction),
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        post("/api/v1/events/{id}/bookmark") {
            val id = call.parameters["id"].orEmpty()
            if (!call.isReadMutationAuthorized(sessionAuthority) || !timeline.contains(id)) {
                call.respondText(
                    "{\"code\":\"${if (timeline.contains(id)) "CSRF_INVALID" else "NOT_FOUND"}\"}",
                    status = if (timeline.contains(id)) HttpStatusCode.Forbidden else HttpStatusCode.NotFound,
                )
                return@post
            }
            annotations.bookmark(id)
            call.respondText("{\"status\":\"bookmarked\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        delete("/api/v1/events/{id}/bookmark") {
            val id = call.parameters["id"].orEmpty()
            if (!call.isReadMutationAuthorized(sessionAuthority) || !timeline.contains(id)) {
                call.respondText(
                    "{\"code\":\"${if (timeline.contains(id)) "CSRF_INVALID" else "NOT_FOUND"}\"}",
                    status = if (timeline.contains(id)) HttpStatusCode.Forbidden else HttpStatusCode.NotFound,
                )
                return@delete
            }
            annotations.removeBookmark(id)
            call.respondText(
                "{\"status\":\"bookmark-removed\"}",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
        }
        put("/api/v1/events/{id}/note") {
            val id = call.parameters["id"].orEmpty()
            if (!call.isReadMutationAuthorized(sessionAuthority) || !timeline.contains(id)) {
                call.respondText(
                    "{\"code\":\"${if (timeline.contains(id)) "CSRF_INVALID" else "NOT_FOUND"}\"}",
                    status = if (timeline.contains(id)) HttpStatusCode.Forbidden else HttpStatusCode.NotFound,
                )
                return@put
            }
            val note = call.receiveText()
            val stored = runCatching { annotations.setNote(id, note) }.isSuccess
            if (!stored) {
                call.respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
                return@put
            }
            call.respondText("{\"status\":\"note-updated\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
        post("/api/v1/session/stop") {
            val session = sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization])
            val expectedOrigin = "http://${call.request.headers[HttpHeaders.Host].orEmpty()}"
            val csrf = call.request.headers[CSRF_HEADER]
            if (session == null) {
                call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.headers[HttpHeaders.Origin] != expectedOrigin) {
                commandAuditLog.recordControlFailure(session.id, "session.stop", "server")
                call.respondText("{\"code\":\"ORIGIN_REJECTED\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (csrf != session.csrfToken) {
                commandAuditLog.recordControlFailure(session.id, "session.stop", "server")
                call.respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
                return@post
            }
            commandAuditLog.recordControlSuccess(session.id, "session.stop", "server")
            call.respondText(
                "{\"status\":\"stop-requested\"}",
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.Accepted,
            )
        }
        webSocket("/api/v1/stream") {
            // Cross-site WebSocket hijacking defense. The stream cookie is already SameSite=Strict,
            // but check Origin explicitly too, matching every mutating REST route. A foreign browser
            // origin is rejected; a legitimate non-browser client (bearer token, no Origin header)
            // is allowed through, so absent-Origin is permitted while a mismatched one is not.
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && origin != "http://${call.request.headers[HttpHeaders.Host].orEmpty()}") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "ORIGIN_REJECTED"))
                return@webSocket
            }
            val authorization =
                call.request.headers[HttpHeaders.Authorization]
                    .orEmpty()
                    .removePrefix("Bearer ")
                    .takeIf(String::isNotBlank)
                    ?: call.streamSessionCookie()
            if (authorization.isBlank() || !sessionAuthority.isAuthorized(authorization)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "AUTH_REQUIRED"))
                return@webSocket
            }
            val hello = incoming.receiveCatching().getOrNull() as? Frame.Text
            if (hello?.data?.decodeToString()?.contains("\"type\":\"client.hello\"") != true) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT.code, "HELLO_REQUIRED"))
                return@webSocket
            }
            send(
                Frame.Text(
                    "{\"type\":\"server.welcome\",\"protocolVersion\":1,\"currentSequence\":${streamHub.currentSequence},\"heartbeatSeconds\":20}",
                ),
            )
            val streamJob =
                launch {
                    streamHub.events.collect { event -> send(Frame.Text(event.toStreamMessage())) }
                }
            try {
                while (isActive) {
                    delay(STREAM_AUTH_RECHECK_MS)
                    if (!sessionAuthority.isAuthorized(authorization)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "AUTH_REVOKED"))
                        break
                    }
                }
            } finally {
                streamJob.cancel()
            }
        }
    }
}

private const val STREAM_AUTH_RECHECK_MS = 100L

/**
 * Reference embedded engine. LAN mode binds to the first eligible non-loopback IPv4 address found
 * on an up, non-virtual, non-point-to-point interface (excludes loopback, VPN/tun, and cellular
 * PPP-style links) -- never `0.0.0.0`, so the server is reachable only via that specific address,
 * not from every route the device happens to have. Android's local-network runtime permission
 * (where applicable) is gated upstream, by the caller, before `start` is ever invoked in LAN mode.
 */
@Suppress("LongParameterList") // Every capture engine/inspector the dashboard can reach is injected here.
class KtorLocalServerEngine(
    private val sessionAuthority: SessionAuthority = SessionAuthority(),
    private val sessionCodeAuthority: SessionCodeAuthority = SessionCodeAuthority(sessionAuthority),
    private val metadata: () -> ServerMetadata = { ServerMetadata() },
    private val sdkHealth: () -> SdkHealthSnapshot = { SdkHealthSnapshot() },
    private val networkTransactions: NetworkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16).also(SecureRandom()::nextBytes))),
    private val socketStore: SocketStore = InMemorySocketStore(),
    private val pushStore: PushStore = InMemoryPushStore(),
    private val stateRegistry: StateRegistry = StateRegistry(),
    featureFlags: SessionFeatureFlags = SessionFeatureFlags(emptyList()),
    featureFlagsEditable: Boolean = false,
    private val mockEngine: MockEngine = MockEngine(emptyList()),
    mocksEditable: Boolean = false,
    private val captureRules: CaptureRuleEngine = CaptureRuleEngine(),
    captureRulesEditable: Boolean = false,
    private val preferencesInspector: PreferencesInspector? = null,
    preferencesEditable: Boolean = false,
    private val databaseInspector: DatabaseInspector? = null,
    databaseEditable: Boolean = false,
    private val fileInspector: FileInspector? = null,
    filesEditable: Boolean = false,
    /** Supplied by the platform facade so events can be persisted; defaults to in-memory. */
    private val timeline: Timeline =
        InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16).also(SecureRandom()::nextBytes))),
    composerEnabled: Boolean = false,
    composerAllowedHosts: Set<String> = emptySet(),
    stateMutationsEnabled: Boolean = false,
    redactionPolicy: RedactionPolicy = RedactionPolicy.default(),
    private val pushSimulator: PushSimulator? = null,
    private val commandAuditLog: CommandAuditLog = InMemoryCommandAuditLog(),
    private val annotations: TimelineAnnotations = InMemoryTimelineAnnotations(),
    private val composerCollections: ComposerCollectionStore = InMemoryComposerCollectionStore(),
    private val composerExecutor: ComposerExecutor = ComposerExecutor(UrlConnectionComposerTransport()),
    /** Durable QA evidence tray; null makes every `/api/v1/evidence` route answer EVIDENCE_UNAVAILABLE. */
    private val evidenceStore: EvidenceStore? = null,
    /** The active app-run's id, scoping every evidence read/write. */
    private val currentSessionId: () -> String = { "current" },
    /** Device/app metadata for the evidence bundle's session.json; honestly omitted when null. */
    private val sessionSnapshotProvider: suspend () -> StoredSession? = { null },
    /** Every retained run, backing GET /api/v1/runs (the previous-run-crashed banner's data source). */
    private val sessionsProvider: suspend () -> List<StoredSession> = { emptyList() },
    /** Delegates to `DevConsoleFacadeProvider.captureScreenshot()`. */
    private val screenshotCapture: suspend () -> ScreenshotResult = { ScreenshotResult.DisabledForBuild },
    private val lanInterfaces: () -> List<NetworkInterface> = {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    },
) : LocalServerEngine {
    /** Public so the host facade can publish captured events into the live dashboard stream. */
    val streamHub: EventStreamHub = EventStreamHub()

    /**
     * Container for the embedded server's own coroutines, carrying [asyncBindFailureHandler] so a
     * failed asynchronous bind is handled instead of reaching the host app's crash handler.
     * `SupervisorJob` so one server's failure never cancels a later one started after a fall-forward.
     * Deliberately never cancelled -- [stop] tears the server down through Ktor's own lifecycle, and
     * cancelling this scope instead would make a stopped engine unable to start again.
     */
    private val serverScope = CoroutineScope(SupervisorJob() + asyncBindFailureHandler)

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var currentEndpoint: Endpoint? = null

    @Volatile
    private var attachmentReader: suspend (String) -> ByteArray? = { null }

    @Volatile
    private var attachmentMetadataReader: suspend (String) -> StoredAttachment? = { null }

    @Volatile
    private var retainedCaptures: RetainedCaptureQuery? = null

    fun withRetainedCaptures(query: RetainedCaptureQuery): KtorLocalServerEngine =
        apply {
            retainedCaptures = query
        }

    @Volatile
    private var runtimeConfiguration =
        RuntimeConfiguration(
            featureFlags = featureFlags,
            featureFlagsEditable = featureFlagsEditable,
            composerEnabled = composerEnabled,
            composerAllowedHosts = composerAllowedHosts,
            stateMutationsEnabled = stateMutationsEnabled,
            redactionPolicy = redactionPolicy,
            mocksEditable = mocksEditable,
            captureRulesEditable = captureRulesEditable,
            preferencesEditable = preferencesEditable,
            databaseEditable = databaseEditable,
            filesEditable = filesEditable,
        )

    @Suppress("LongParameterList") // Mirrors every editing capability the dashboard's Data rail exposes.
    @Synchronized
    fun reconfigure(
        featureFlags: SessionFeatureFlags,
        composerEnabled: Boolean,
        composerAllowedHosts: Set<String>,
        stateMutationsEnabled: Boolean,
        redactionPolicy: RedactionPolicy,
        mocksEditable: Boolean = false,
        captureRulesEditable: Boolean = false,
        preferencesEditable: Boolean = false,
        databaseEditable: Boolean = false,
        filesEditable: Boolean = false,
        featureFlagsEditable: Boolean = false,
    ) {
        runtimeConfiguration =
            RuntimeConfiguration(
                featureFlags = featureFlags,
                featureFlagsEditable = featureFlagsEditable,
                composerEnabled = composerEnabled,
                composerAllowedHosts = composerAllowedHosts,
                stateMutationsEnabled = stateMutationsEnabled,
                redactionPolicy = redactionPolicy,
                mocksEditable = mocksEditable,
                captureRulesEditable = captureRulesEditable,
                preferencesEditable = preferencesEditable,
                databaseEditable = databaseEditable,
                filesEditable = filesEditable,
            )
    }

    fun withAttachmentReader(reader: suspend (String) -> ByteArray?): KtorLocalServerEngine =
        apply {
            attachmentReader =
                reader
        }

    fun withAttachmentMetadataReader(reader: suspend (String) -> StoredAttachment?): KtorLocalServerEngine =
        apply {
            attachmentMetadataReader = reader
        }

    override suspend fun start(request: StartRequest): ServerStartResult =
        synchronized(this) {
            if (engine != null) return@synchronized ServerStartResult.Failed("Server already running")
            if (
                request.portRange.isEmpty() ||
                request.portRange.first !in 1..65_535 ||
                request.portRange.last !in 1..65_535
            ) {
                return@synchronized ServerStartResult.InvalidConfiguration(
                    "portRange must be non-empty and contain only valid TCP ports",
                )
            }
            // Validated here rather than letting issueCode() throw later: by that point the port
            // is already bound, and an exception would strand a running server with no code. The
            // upper bound also keeps nowEpochMs() + ttl from overflowing into instant expiry.
            if (request.sessionCodeTtlMs !in 1..MAX_SESSION_CODE_TTL_MS) {
                return@synchronized ServerStartResult.InvalidConfiguration(
                    "sessionCodeTtlMs must be positive and at most 24 hours",
                )
            }

            val effectiveBinding = request.bindingMode // Explicit LOOPBACK or LAN; AUTO no longer exists.

            val bindHost =
                when (effectiveBinding) {
                    BindingMode.LOOPBACK -> "127.0.0.1"
                    BindingMode.LAN ->
                        selectLanAddress(lanInterfaces())
                            ?: return@synchronized ServerStartResult.NoEligibleNetwork(
                                "No active non-loopback IPv4 network interface was found",
                            )
                }

            request.portRange.forEach { port ->
                if (!isPortAvailable(bindHost, port)) return@forEach
                val endpoint = Endpoint(bindHost, port, effectiveBinding)
                val configuration = runtimeConfiguration
                val candidate =
                    runCatching {
                        serverScope
                            .embeddedServer(
                                CIO,
                                host = endpoint.host,
                                port = endpoint.port,
                                parentCoroutineContext = asyncBindFailureHandler,
                            ) {
                                devConsoleModule(sessionAuthority, sessionCodeAuthority) {
                                    allowedHosts = setOf("localhost", endpoint.host)
                                    this.networkTransactions = this@KtorLocalServerEngine.networkTransactions
                                    this.socketStore = this@KtorLocalServerEngine.socketStore
                                    this.pushStore = this@KtorLocalServerEngine.pushStore
                                    this.stateRegistry = this@KtorLocalServerEngine.stateRegistry
                                    this.featureFlags = configuration.featureFlags
                                    this.featureFlagsEditable = configuration.featureFlagsEditable
                                    this.mockEngine = this@KtorLocalServerEngine.mockEngine
                                    this.mocksEditable = configuration.mocksEditable
                                    this.captureRules = this@KtorLocalServerEngine.captureRules
                                    this.captureRulesEditable = configuration.captureRulesEditable
                                    this.preferencesInspector = this@KtorLocalServerEngine.preferencesInspector
                                    this.preferencesEditable = configuration.preferencesEditable
                                    this.databaseInspector = this@KtorLocalServerEngine.databaseInspector
                                    this.databaseEditable = configuration.databaseEditable
                                    this.fileInspector = this@KtorLocalServerEngine.fileInspector
                                    this.filesEditable = configuration.filesEditable
                                    this.timeline = this@KtorLocalServerEngine.timeline
                                    this.metadata = this@KtorLocalServerEngine.metadata()
                                    this.sdkHealth = this@KtorLocalServerEngine.sdkHealth
                                    this.streamHub = this@KtorLocalServerEngine.streamHub
                                    this.annotations = this@KtorLocalServerEngine.annotations
                                    this.stateMutationsEnabled = configuration.stateMutationsEnabled
                                    this.composerEnabled = configuration.composerEnabled
                                    this.composerAllowedHosts = configuration.composerAllowedHosts
                                    this.composerExecutor = this@KtorLocalServerEngine.composerExecutor
                                    this.composerCollections = this@KtorLocalServerEngine.composerCollections
                                    this.pushSimulator = this@KtorLocalServerEngine.pushSimulator
                                    this.commandAuditLog = this@KtorLocalServerEngine.commandAuditLog
                                    this.redactionPolicy = configuration.redactionPolicy
                                    this.attachmentReader = this@KtorLocalServerEngine.attachmentReader
                                    this.attachmentMetadataReader = this@KtorLocalServerEngine.attachmentMetadataReader
                                    this.retainedCaptures = this@KtorLocalServerEngine.retainedCaptures
                                    this.evidenceStore = this@KtorLocalServerEngine.evidenceStore
                                    this.currentSessionId = this@KtorLocalServerEngine.currentSessionId
                                    this.sessionSnapshotProvider = this@KtorLocalServerEngine.sessionSnapshotProvider
                                    this.sessionsProvider = this@KtorLocalServerEngine.sessionsProvider
                                    this.screenshotCapture = this@KtorLocalServerEngine.screenshotCapture
                                    boundEndpoint = { currentEndpoint }
                                }
                            }.start(wait = false)
                    }.getOrNull() ?: return@forEach

                if (!isServerBound(endpoint.host, endpoint.port)) {
                    runCatching { candidate.stop(100, 200) }
                    return@forEach
                }

                engine = candidate
                currentEndpoint = endpoint
                return@synchronized ServerStartResult.Started(
                    endpoint,
                    sessionCode = sessionCodeAuthority.issueCode(endpoint, request.sessionCodeTtlMs),
                )
            }
            return@synchronized ServerStartResult.PortUnavailable(request.portRange)
        }

    override suspend fun stop() =
        synchronized(this) {
            // A throwing engine.stop() (e.g. the CIO engine's own shutdown machinery blowing up)
            // must not skip the state reset below -- otherwise `engine` is left non-null forever,
            // and every subsequent start() permanently answers "Server already running" until the
            // process dies. The finally block guarantees the reset runs whether or not stop() threw.
            try {
                engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
                Unit
            } finally {
                engine = null
                currentEndpoint = null
                sessionAuthority.reset()
                sessionCodeAuthority.reset()
            }
        }

    override fun bindAddressChanged(): Boolean = bindAddressChanged(currentEndpoint, selectLanAddress(lanInterfaces()))

    private data class RuntimeConfiguration(
        val featureFlags: SessionFeatureFlags,
        val featureFlagsEditable: Boolean,
        val composerEnabled: Boolean,
        val composerAllowedHosts: Set<String>,
        val stateMutationsEnabled: Boolean,
        val redactionPolicy: RedactionPolicy,
        val mocksEditable: Boolean,
        val captureRulesEditable: Boolean,
        val preferencesEditable: Boolean,
        val databaseEditable: Boolean,
        val filesEditable: Boolean,
    )
}

/**
 * The address-change detection decision, split out from [KtorLocalServerEngine.bindAddressChanged] as a plain
 * function so it's testable without a real [NetworkInterface]. A null [liveAddress] (no interface
 * currently eligible) is treated as inconclusive, not a change -- see the interface kdoc.
 */
internal fun bindAddressChanged(
    bound: Endpoint?,
    liveAddress: String?,
): Boolean = bound != null && bound.bindingMode == BindingMode.LAN && liveAddress != null && liveAddress != bound.host

/** First non-loopback, non-link-local IPv4 address on an up, non-virtual, non-point-to-point interface. */
internal fun selectLanAddress(interfaces: List<NetworkInterface>): String? =
    interfaces
        .asSequence()
        .filter { runCatching { it.isUp }.getOrDefault(false) }
        .filterNot { runCatching { it.isLoopback }.getOrDefault(true) }
        .filterNot { runCatching { it.isVirtual }.getOrDefault(true) }
        .filterNot { runCatching { it.isPointToPoint }.getOrDefault(true) }
        .flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress }
        .filterNot { it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .firstOrNull()

private const val MAX_WEBSOCKET_FRAME_BYTES = 64L * 1024L
private const val CSRF_HEADER = "X-DevConsole-CSRF"
private const val STREAM_SESSION_COOKIE = "DevConsoleStreamSession"
private const val DEFAULT_RELATED_EVENT_WINDOW_MS = 1_000L
private const val MAX_RELATED_EVENT_WINDOW_MS = 60_000L
private val COMPOSER_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
private val MOCK_RULE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
private const val MOCK_RULE_MAX_DELAY_MS = 30_000L
private const val MAX_MOCK_RULE_HEADERS = 50

/**
 * Newline-delimited `Name: value` pairs, the same convention the Composer form uses -- one line per
 * response header. Blank lines are skipped; a malformed line (no `:`) throws, which the caller turns
 * into `VALIDATION_FAILED` rather than silently dropping a header the user typed.
 */
private val MOCK_HEADER_NAME = Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")

// OkHttp's Headers.Builder rejects names/values outside these ranges with an
// IllegalArgumentException at interception time — i.e. on the HOST APP's request thread.
// Validate at the authoring boundary instead so a typo is a 400, not a runtime crash.
private fun validMockHeader(
    name: String,
    value: String,
): Boolean = name.matches(MOCK_HEADER_NAME) && value.all { it == '\t' || it in ' '..'~' }

private fun String?.parseMockRuleHeaders(): Map<String, String> =
    orEmpty()
        .split('\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .also { require(it.size <= MAX_MOCK_RULE_HEADERS) }
        .associate { it.splitComposerPair(':') }
        .onEach { (name, value) -> require(validMockHeader(name, value)) }

private fun io.ktor.server.application.ApplicationCall.setStreamSessionCookie(token: String) {
    response.headers.append(
        HttpHeaders.SetCookie,
        "$STREAM_SESSION_COOKIE=$token; Path=/api/v1/stream; HttpOnly; SameSite=Strict",
    )
}

private fun io.ktor.server.application.ApplicationCall.clearStreamSessionCookie() {
    response.headers.append(
        HttpHeaders.SetCookie,
        "$STREAM_SESSION_COOKIE=; Path=/api/v1/stream; HttpOnly; SameSite=Strict; Max-Age=0",
    )
}

private fun io.ktor.server.application.ApplicationCall.streamSessionCookie(): String =
    request.headers[HttpHeaders.Cookie]
        .orEmpty()
        .split(';')
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.substringBefore('=') == STREAM_SESSION_COOKIE }
        ?.substringAfter('=', missingDelimiterValue = "")
        .orEmpty()

private class SlidingWindowRateLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val eventsByKey = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = nowMs()
        purgeExpiredKeys(now)
        val events = eventsByKey.getOrPut(key) { ArrayDeque() }
        while (events.firstOrNull()?.let { now - it >= windowMs } == true) events.removeFirst()
        if (events.size >= maxEvents) return false
        events.addLast(now)
        return true
    }

    private fun purgeExpiredKeys(now: Long) {
        val iterator = eventsByKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val deque = entry.value
            while (deque.firstOrNull()?.let { now - it >= windowMs } == true) deque.removeFirst()
            if (deque.isEmpty()) {
                iterator.remove()
            }
        }
    }
}

/**
 * Keeps a *detected and recovered* bind failure from being reported as an unhandled crash.
 *
 * CIO binds asynchronously: `start(wait = false)` returns before the accept loop has the port, so
 * losing the race between [isPortAvailable]'s probe and the real bind throws inside Ktor's own
 * `httpServer` accept coroutine -- long after the `runCatching` around `start()` has returned.
 * With nothing on the server's parent context that exception reaches the thread's default uncaught
 * handler, which on Android is the *host app's* crash handler: a debug console taking down the app
 * it exists to observe, for a condition it already handles correctly.
 *
 * [KtorLocalServerEngine.start] notices the failure the honest way -- `isServerBound` returns false
 * and the loop falls forward to the next port -- so nothing is being hidden here that the engine
 * does not already act on. The handler is deliberately silent rather than logging: this runs inside
 * a host application, and the SDK's contract is that its own recovery is never the host's problem.
 *
 * Found via a flaky test: Gradle runs `testDebugUnitTest` and `testReleaseUnitTest` in parallel
 * (`org.gradle.parallel=true`), both binding this module's fixed 8400..8419 range, and the loser's
 * escaped BindException was landing on whichever unrelated test called `runTest` next as
 * `UncaughtExceptionsBeforeTest`.
 */
private val asyncBindFailureHandler = CoroutineExceptionHandler { _, _ -> }

/** A best-effort preflight lets the engine advance over occupied ports before CIO starts its async bind. */

private fun isPortAvailable(
    host: String,
    port: Int,
): Boolean =
    runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(host, port))
        }
    }.isSuccess

/** Verifies that the asynchronous CIO engine bound successfully by probing the target port. */
private fun isServerBound(
    host: String,
    port: Int,
    maxWaitMs: Long = 500L,
): Boolean {
    val deadline = System.currentTimeMillis() + maxWaitMs
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 50)
                return true
            }
        } catch (_: Exception) {
            Thread.sleep(10)
        }
    }
    return false
}

private fun CommandAuditLog.recordControlSuccess(
    browserSessionId: String,
    commandType: String,
    target: String,
    parameters: Map<String, String> = emptyMap(),
) = record(
    CommandAuditEvent(
        timestampEpochMs = System.currentTimeMillis(),
        browserSessionId = browserSessionId,
        commandType = commandType,
        target = target,
        result = CommandAuditResult.SUCCESS,
        parameters = parameters,
    ),
)

private fun CommandAuditLog.recordControlFailure(
    browserSessionId: String,
    commandType: String,
    target: String,
) = record(
    CommandAuditEvent(
        timestampEpochMs = System.currentTimeMillis(),
        browserSessionId = browserSessionId,
        commandType = commandType,
        target = target,
        result = CommandAuditResult.REJECTED,
    ),
)

private fun CommandAuditLog.recordExport(
    browserSessionId: String,
    result: CommandAuditResult,
    target: String,
) = record(
    CommandAuditEvent(
        timestampEpochMs = System.currentTimeMillis(),
        browserSessionId = browserSessionId,
        commandType = "export.create",
        target = target,
        result = result,
    ),
)

private fun Timeline.collectForExport(
    fromEpochMs: Long?,
    toEpochMs: Long?,
): List<StoredEvent> {
    val events = mutableListOf<StoredEvent>()
    var query =
        TimelineQuery(limit = TimelineQuery.MAX_PAGE_LIMIT, sort = TimelineSort.ASC)
            .withTimeRange(fromEpochMs, toEpochMs)
    while (events.size < MAX_EXPORT_EVENTS) {
        val page = page(query) as? TimelinePage.Success ?: break
        events += page.events.take(MAX_EXPORT_EVENTS - events.size)
        if (!page.hasMore || page.nextCursor == null) break
        query = query.withCursor(page.nextCursor)
    }
    return events
}

/** Everything [ExportRequest] needs, resolved from request parameters shared by the write and estimate routes. */
private data class ExportPreparation(
    val scope: ExportScope,
    val metadataOnly: Boolean,
    val maxBytes: Long,
    val exportSessionId: String,
    val scopedEvents: List<StoredEvent>,
    val attachments: Map<String, ByteArray>,
)

/** Shared by [prepareExport] and the `scope=EVIDENCE` branch of both export routes. */
private fun Parameters.resolveMaxBytes(): Long? {
    val maxBytes = this["maxBytes"]?.toLongOrNull() ?: DEFAULT_EXPORT_LIMIT_BYTES
    return maxBytes.takeIf { it in 1..DEFAULT_EXPORT_LIMIT_BYTES }
}

/**
 * Parses and resolves the scope/metadataOnly/maxBytes/sessionId parameters shared by
 * `POST /api/v1/exports` (form body) and `GET /api/v1/exports/estimate` (query string) -- both a
 * [io.ktor.server.request.receiveParameters] result and `call.request.queryParameters` are a
 * [Parameters], so one function serves either caller. Returns null on any validation failure.
 *
 * This is one linear parse of the shared export parameters with a validation exit per
 * parameter; splitting it would scatter a single request contract across helpers.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private suspend fun prepareExport(
    parameters: Parameters,
    timeline: Timeline,
    retainedCaptures: RetainedCaptureQuery?,
    attachmentReader: suspend (String) -> ByteArray?,
): ExportPreparation? {
    val scope =
        runCatching {
            when (parameters["scope"]?.uppercase() ?: "WHOLE_SESSION") {
                "WHOLE_SESSION" -> ExportScope.WholeSession
                "TIME_RANGE" ->
                    ExportScope.TimeRange(
                        fromEpochMs = parameters["fromEpochMs"]?.toLongOrNull() ?: error("fromEpochMs required"),
                        toEpochMs = parameters["toEpochMs"]?.toLongOrNull() ?: error("toEpochMs required"),
                    )
                "EVENT_IDS" ->
                    ExportScope.EventIds(
                        parameters.getAll("eventId").orEmpty().toSet(),
                    )
                else -> error("Unknown export scope")
            }
        }.getOrNull() ?: return null
    val metadataOnly =
        when (parameters["metadataOnly"]?.lowercase()) {
            null, "", "false" -> false
            "true" -> true
            else -> return null
        }
    val maxBytes = parameters["maxBytes"]?.toLongOrNull() ?: DEFAULT_EXPORT_LIMIT_BYTES
    if (maxBytes !in 1..DEFAULT_EXPORT_LIMIT_BYTES) return null
    val range = scope as? ExportScope.TimeRange
    val requestedSessionId = parameters["sessionId"]?.takeIf(String::isNotBlank)
    if (requestedSessionId != null && requestedSessionId.length > MAX_EXPORT_SESSION_ID_LENGTH) return null
    val liveEvents =
        timeline.collectForExport(
            fromEpochMs = range?.fromEpochMs,
            toEpochMs = range?.toEpochMs,
        )
    val durableEvents = retainedCaptures?.eventsForExport(requestedSessionId).orEmpty()
    // Current-session persistence may lag the live timeline; merge by ID rather than choosing
    // one source. Explicit retained sessions naturally contribute no live rows.
    val events =
        (durableEvents + liveEvents)
            .asSequence()
            .filter { event -> range == null || event.wallTimeMs in range.fromEpochMs..range.toEpochMs }
            .distinctBy(StoredEvent::id)
            .sortedWith(compareBy(StoredEvent::wallTimeMs).thenBy(StoredEvent::sequence))
            .toList()
    val exportSessionId = requestedSessionId ?: events.lastOrNull()?.sessionId ?: "current"
    val scopedEvents =
        when (scope) {
            ExportScope.WholeSession -> events
            is ExportScope.TimeRange -> events
            is ExportScope.EventIds -> events.filter { it.id in scope.ids }
            // Never reached: the EVIDENCE scope is intercepted by both export routes before
            // prepareExport is ever called, and the scope parser above rejects "EVIDENCE" as unknown.
            ExportScope.Evidence -> emptyList()
        }.filter { it.sessionId == exportSessionId }
    val attachments =
        if (metadataOnly) {
            emptyMap()
        } else {
            buildMap {
                scopedEvents
                    .mapNotNull(StoredEvent::attachmentId)
                    .distinct()
                    .forEach { attachmentId ->
                        try {
                            attachmentReader(attachmentId)?.let { put(attachmentId, it) }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A missing/corrupt attachment degrades this export, not the host app.
                        }
                    }
            }
        }
    return ExportPreparation(scope, metadataOnly, maxBytes, exportSessionId, scopedEvents, attachments)
}

/**
 * Copies every entry [timelineZip] already holds into [destination], then appends the network HAR and
 * Postman exports (over this session's transactions) plus app metadata -- the same enrichment
 * [io.devconsole.InspectorExporter]'s on-device session ZIP carries, so the browser and on-device
 * bundles agree. Entry names are constant literals, so no caller input can introduce a traversal entry.
 */
private fun appendSessionBundleEntries(
    timelineZip: File,
    destination: File,
    transactions: List<NetworkTransaction>,
    metadata: ServerMetadata,
    redaction: RedactionEngine,
) {
    ZipOutputStream(destination.outputStream().buffered()).use { output ->
        ZipInputStream(timelineZip.inputStream().buffered()).use { input ->
            generateSequence(input.nextEntry) { input.nextEntry }.forEach { entry ->
                output.putNextEntry(ZipEntry(entry.name))
                input.copyTo(output)
                output.closeEntry()
            }
        }
        output.putBundleEntry("network.har", NetworkExport.toHarTransactions(transactions, redaction))
        output.putBundleEntry("network.postman_collection.json", NetworkExport.toPostman(transactions, redaction))
        output.putBundleEntry("metadata.json", metadata.bundleMetadataJson())
    }
}

private fun ZipOutputStream.putBundleEntry(
    name: String,
    content: String,
) {
    putNextEntry(ZipEntry(name))
    write(content.encodeToByteArray())
    closeEntry()
}

/** Non-sensitive app metadata, matching the shape `AndroidInspectorExporter` writes into `metadata.json`. */
private fun ServerMetadata.bundleMetadataJson(): String =
    "{\"appDisplayName\":\"${appDisplayName.escapeJson()}\"," +
        "\"appPackageName\":\"${appPackageName.escapeJson()}\"," +
        "\"appVersionName\":\"${appVersionName.escapeJson()}\"," +
        "\"buildVariant\":\"${buildVariant.escapeJson()}\"}"

private fun String.commandAuditIdentity(method: String): Pair<String, String>? =
    when {
        this == "/api/v1/composer/execute" -> "composer.execute" to "request"
        this == "/api/v1/composer/import" -> "composer.import" to "curl"
        this == "/api/v1/composer/collections" && method == "POST" -> "composer.collection.save" to "collection"
        startsWith("/api/v1/composer/collections/") && method == "DELETE" ->
            "composer.collection.delete" to substringAfterLast('/')
        startsWith("/api/v1/network/transactions/") && endsWith("/resend") ->
            "network.resend" to removePrefix("/api/v1/network/transactions/").removeSuffix("/resend")
        this == "/api/v1/capture-rules" && method == "POST" -> "capture.rule.upsert" to "rule"
        startsWith("/api/v1/capture-rules/") && endsWith("/enabled") ->
            "capture.rule.enabled" to removePrefix("/api/v1/capture-rules/").removeSuffix("/enabled")
        startsWith("/api/v1/capture-rules/") && method == "DELETE" -> "capture.rule.delete" to substringAfterLast('/')
        this == "/api/v1/mocks/disable-all" -> "mock.disable_all" to "mocks"
        this == "/api/v1/mocks/enabled" -> "mock.enabled" to "mocks"
        this == "/api/v1/mocks/rules" && method == "POST" -> "mock.rule.upsert" to "rule"
        startsWith("/api/v1/mocks/rules/") && endsWith("/enabled") ->
            "mock.rule.enabled" to removePrefix("/api/v1/mocks/rules/").removeSuffix("/enabled")
        startsWith("/api/v1/mocks/rules/") && method == "DELETE" -> "mock.rule.delete" to substringAfterLast('/')
        contains("/mutations/") -> "state.mutation" to removePrefix("/api/v1/state/").replace("/mutations/", ".")
        startsWith("/api/v1/preferences/") && method == "POST" -> "preferences.entry.set" to substringAfterLast('/')
        startsWith("/api/v1/preferences/") && method == "DELETE" ->
            "preferences.entry.remove" to
                substringAfterLast('/')
        startsWith("/api/v1/database/") && endsWith("/sql") && method == "POST" ->
            "database.sql.execute" to removePrefix("/api/v1/database/").removeSuffix("/sql")
        startsWith("/api/v1/files/") && endsWith("/rename") && method == "POST" ->
            "files.rename" to removeSuffix("/rename").substringAfterLast('/')
        startsWith("/api/v1/files/") && method == "PUT" -> "files.create" to substringAfterLast('/')
        startsWith("/api/v1/files/") && method == "POST" -> "files.replace" to substringAfterLast('/')
        startsWith("/api/v1/files/") && method == "DELETE" -> "files.delete" to substringAfterLast('/')
        this == "/api/v1/push/simulate" -> "push.simulate" to "push"
        this == "/api/v1/exports" && method == "POST" -> "export.create" to "export"
        this == "/api/v1/evidence" && method == "POST" -> "evidence.flag" to "evidence"
        this == "/api/v1/evidence" && method == "DELETE" -> "evidence.clear" to "evidence"
        startsWith("/api/v1/evidence/") && method == "DELETE" -> "evidence.unflag" to substringAfterLast('/')
        this == "/api/v1/evidence/report" && method == "PUT" -> "evidence.report.save" to "evidence"
        this == "/api/v1/screenshots" && method == "POST" -> "screenshot.capture" to "screenshot"
        this == "/api/v1/network/har" && method == "POST" -> "network.export.har" to "har"
        this == "/api/v1/network/postman" && method == "POST" -> "network.export.postman" to "postman"
        else -> null
    }

private const val MAX_SESSION_CODE_TTL_MS = 24L * 60 * 60 * 1000
private const val MAX_STATE_MUTATION_INPUT_BYTES = 64 * 1024
private const val MAX_SQL_INPUT_BYTES = 8 * 1024
private const val MAX_EXPORT_EVENTS = 50_000
private const val MAX_EXPORT_SESSION_ID_LENGTH = 256
private const val MAX_FILE_WRITE_INPUT_BYTES = 256 * 1024
private const val MAX_DOWNLOAD_FILENAME_LENGTH = 255
private const val PRINTABLE_ASCII_MIN = 0x20
private const val PRINTABLE_ASCII_MAX = 0x7E

// Reuse EvidenceStore's own caps (sdk:storage-api) rather than re-declaring them here, so a
// rejection at this route boundary is reported as a clean VALIDATION_FAILED before ever reaching
// the store's require() checks, and the two can never drift apart the way they once did.
private val MAX_EVIDENCE_LABEL_LENGTH = EvidenceStore.MAX_LABEL_LENGTH
private val MAX_EVIDENCE_TEXT_LENGTH = EvidenceStore.MAX_TEXT_LENGTH

// No shared equivalent: MAX_LABEL_LENGTH covers the materialized item label, not the raw subject id
// a caller supplies before materialization, and the two are validated at different points with
// different failure modes. Left local deliberately rather than inventing a shared constant nothing
// else needs.
private const val MAX_EVIDENCE_SUBJECT_ID_LENGTH = 512
private const val MAX_TIMELINE_SCAN_PAGES = 200

// GET /api/v1/evidence's own defensive cap on a single response -- matches EvidenceStore's
// per-session item quota, so the shipped dashboard (which never sends ?limit=) sees no behavior
// change, while a store implementation that ever allowed more items than that can no longer serialize
// an unbounded response at 120 req/min (see readQueryLimiter).
private val MAX_EVIDENCE_ITEMS_PER_RESPONSE = EvidenceStore.MAX_ITEMS_PER_SESSION

private fun SessionAuthority.bearerSession(authorization: String?) =
    authorization
        .orEmpty()
        .removePrefix("Bearer ")
        .takeIf { it.isNotBlank() }
        ?.let { sessionForToken(it) }

/**
 * Shared entry gate for every capture-rule mutation route: authenticated bearer session, then origin
 * and CSRF, then the `captureRules` editing capability. Returns null once it has already answered, so
 * callers only need `?: return@post`. The capability is checked last so a cross-site request is
 * rejected as CSRF rather than leaking whether editing is enabled, and every rejection past
 * authentication is audited under [commandType].
 */
private suspend fun io.ktor.server.application.ApplicationCall.captureRuleControlSession(
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
    captureRulesEditable: Boolean,
    commandType: String,
    target: String,
): BrowserSession? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    if (!captureRulesEditable) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CAPTURE_RULES_DISABLED\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    return session
}

/**
 * Shared entry gate for every mock-rule mutation route (create, delete, per-rule enable/disable):
 * Authenticated bearer session, then origin and CSRF, then the `mocks` editing capability. Mirrors
 * [captureRuleControlSession]; the global on/off toggle (`/api/v1/mocks/disable-all`) intentionally
 * stays ungated by this capability, preserving its existing authenticated-only behavior.
 */
private suspend fun io.ktor.server.application.ApplicationCall.mockRuleControlSession(
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
    mocksEditable: Boolean,
    commandType: String,
    target: String,
): BrowserSession? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    if (!mocksEditable) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"MOCKS_DISABLED\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    return session
}

/**
 * Shared entry gate for every preference-entry mutation route (set, remove): authenticated bearer session,
 * then origin and CSRF, then the `preferences` editing capability. Mirrors [mockRuleControlSession];
 * listing preference files/entries stays ungated by this capability, matching the read/mutation split
 * used everywhere else in this module.
 */
private suspend fun io.ktor.server.application.ApplicationCall.preferencesControlSession(
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
    preferencesEditable: Boolean,
    commandType: String,
    target: String,
): BrowserSession? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    if (!preferencesEditable) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"PREFERENCES_DISABLED\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    return session
}

/**
 * Shared entry gate for every file mutation route (delete, create, replace, rename): authenticated bearer
 * session, then origin and CSRF, then the `files` editing capability. Mirrors [mockRuleControlSession];
 * browsing roots/entries and previewing a file stay ungated by this capability. The download route is
 * a GET so it does not go through here, but it enforces the same `files` capability inline -- see the
 * comment on that route.
 */
private suspend fun io.ktor.server.application.ApplicationCall.filesControlSession(
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
    filesEditable: Boolean,
    commandType: String,
    target: String,
): BrowserSession? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    if (!filesEditable) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"FILES_DISABLED\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    return session
}

/**
 * Shared entry gate for every route that dispatches an outbound request through the SDK-owned
 * composer transport (Composer's own execute route, and Network's "Resend"): authenticated bearer
 * session, then origin and CSRF, then the same `composerEnabled` capability Composer itself gates
 * on -- reusing [io.devconsole.composer]'s `COMPOSER_DISABLED` code rather than inventing a new one,
 * since both routes share one execution path and one on/off switch.
 */
@Suppress("ReturnCount") // One early-exit per gate stage (auth, CSRF, capability) reads clearest.
private suspend fun io.ktor.server.application.ApplicationCall.composerExecutionControlSession(
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
    composerEnabled: Boolean,
    commandType: String,
    target: String,
): BrowserSession? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    if (!composerEnabled) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"COMPOSER_DISABLED\"}", status = HttpStatusCode.NotFound)
        return null
    }
    return session
}

/**
 * Shared entry gate for every evidence-tray and screenshot mutation route: authenticated bearer
 * session, then origin and CSRF. Unlike mocks/capture-rules/preferences/files/database, there is no
 * separate editing capability here -- flagging evidence or capturing a screenshot never lets a
 * session touch host application state, so this mirrors [isReadMutationAuthorized]'s two-stage gate
 * (auth, then CSRF) rather than [mockRuleControlSession]'s three-stage one, while still auditing every
 * rejection past authentication under [commandType] like every other command route.
 */
@Suppress("ReturnCount") // One early-exit per gate stage (auth, CSRF) reads clearest.
private suspend fun io.ktor.server.application.ApplicationCall.evidenceControlSession(
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
    commandType: String,
    target: String,
): BrowserSession? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordControlFailure(session.id, commandType, target)
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    return session
}

/** Preference values may hold arbitrary (already-redacted) text, so every field is escaped. */
private fun PreferencesFileData.json(): String {
    val entryJson =
        entries.joinToString(",") { entry ->
            "{\"key\":\"${entry.key.escapeJson()}\",\"value\":\"${entry.value.escapeJson()}\"," +
                "\"type\":\"${entry.type.escapeJson()}\",\"redacted\":${entry.redacted}}"
        }
    return "{\"name\":\"${name.escapeJson()}\",\"entries\":[$entryJson]}"
}

private fun SessionCodeInfo.json(): String =
    "{\"browserUrl\":\"${browserUrl.escapeJson()}\",\"code\":\"${code.escapeJson()}\"," +
        "\"expiresAtEpochMs\":$expiresAtEpochMs}"

private fun DatabaseListingData.json(): String {
    val tableJson = tables.joinToString(",") { "{\"name\":\"${it.name.escapeJson()}\",\"rowCount\":${it.rowCount}}" }
    return "{\"name\":\"${name.escapeJson()}\",\"sizeBytes\":$sizeBytes,\"tables\":[$tableJson]}"
}

/**
 * Cell text is already redacted by the engine, but is arbitrary app data, so every value is escaped.
 * [DatabaseQueryData.rowIds] is not app data -- it is the engine's own `rowid`, never masked -- so it
 * is emitted as a plain number/null array rather than escaped strings.
 */
private fun DatabaseQueryData.jsonFields(): String {
    val columnJson = columns.joinToString(",") { "\"${it.escapeJson()}\"" }
    val rowJson = rows.joinToString(",") { row -> "[${row.joinToString(",") { "\"${it.escapeJson()}\"" }}]" }
    val rowIdJson = rowIds.joinToString(",") { it?.toString() ?: "null" }
    return "\"columns\":[$columnJson],\"rows\":[$rowJson],\"truncated\":$truncated,\"rowIds\":[$rowIdJson]"
}

private fun DatabaseQueryData.json(): String = "{${jsonFields()}}"

private fun FileListingData.json(): String {
    val entryJson =
        entries.joinToString(",") { entry ->
            "{\"name\":\"${entry.name.escapeJson()}\",\"relativePath\":\"${entry.relativePath.escapeJson()}\"," +
                "\"isDirectory\":${entry.isDirectory},\"sizeBytes\":${entry.sizeBytes}," +
                "\"lastModifiedEpochMs\":${entry.lastModifiedEpochMs}}"
        }
    return "{\"root\":\"${root.escapeJson()}\",\"relativePath\":\"${relativePath.escapeJson()}\"," +
        "\"entries\":[$entryJson]}"
}

/**
 * Bounds a file's leaf name to header-safe characters before it lands in a `Content-Disposition`
 * value: strips quotes/backslashes (which would break out of the quoted-string form) and every
 * non-printable-ASCII byte (which includes CR/LF, closing off any header-injection attempt).
 */
private fun String.sanitizeFilenameForHeader(): String =
    filter { it.code in PRINTABLE_ASCII_MIN..PRINTABLE_ASCII_MAX && it != '"' && it != '\\' }
        .take(MAX_DOWNLOAD_FILENAME_LENGTH)
        .ifBlank { "download" }

/** A `Host` header successfully parsed into a bare name and an optional numeric port. */
private data class ParsedHostHeader(
    val name: String,
    val port: Int?,
)

/** Inclusive bounds of a valid TCP port, per RFC 6335 §6 (port 0 is reserved/unassigned). */
private const val MIN_VALID_PORT = 1
private const val MAX_VALID_PORT = 65_535

/**
 * Parses a `Host` header per the `host[:port]` grammar (`host` = IP-literal / IPv4address /
 * reg-name -- RFC 7230 §5.4 / RFC 3986 §3.2.2), returning `null` for anything that doesn't fit
 * that shape cleanly. This is deliberately strict rather than merely taking everything before the
 * first colon: a value like `localhost:4321; frame-ancestors evil.example.com` has an allowed
 * pre-colon name, but the port position must parse cleanly via `toIntOrNull()` and land in
 * [MIN_VALID_PORT]..[MAX_VALID_PORT] -- the moment anything else (a `;`, a space, another colon)
 * follows the colon, parsing fails and the whole header is rejected rather than partially trusted.
 * Note that `toIntOrNull()` is looser than "only ASCII digits": it also accepts a leading `+`,
 * leading zeros, and non-ASCII Unicode digit characters (e.g. fullwidth `８０８０`), all of which
 * parse successfully here. That is harmless -- the range check still applies, and any port this
 * function returns is always re-rendered as a plain ASCII `Int` by every downstream consumer, so
 * nothing about that looser acceptance is ever reflected back verbatim -- but it does mean this
 * function's port position is *not* an ASCII-digits-only gate the way the surrounding prose used to
 * imply. Callers must never fall back to reflecting the raw header when this returns `null`.
 *
 * Also handles a bracketed IPv6 literal (`[::1]:8080`), stripping the brackets from [name] so it
 * compares equal to the unbracketed form `allowedHosts` uses; callers that re-embed [name] in a
 * URI must re-add brackets themselves when it contains a `:`.
 */
@Suppress("ReturnCount")
// Early returns are the clearest form for a validating parser: each guards one way the input can
// be malformed, right where that shape is checked. Collapsing them would only obscure the RFC
// grammar this enforces, so the limit is suppressed here rather than restructured.
private fun parseHostHeader(raw: String): ParsedHostHeader? {
    if (raw.isEmpty()) return null
    if (raw.startsWith("[")) {
        val closeBracket = raw.indexOf(']')
        if (closeBracket < 0) return null
        val name = raw.substring(1, closeBracket)
        val rest = raw.substring(closeBracket + 1)
        return when {
            rest.isEmpty() -> ParsedHostHeader(name, null)
            rest.startsWith(":") -> {
                val port =
                    rest.substring(1).toIntOrNull()?.takeIf { it in MIN_VALID_PORT..MAX_VALID_PORT }
                        ?: return null
                ParsedHostHeader(name, port)
            }
            else -> null
        }
    }
    val colonIndex = raw.indexOf(':')
    if (colonIndex < 0) return ParsedHostHeader(raw, null)
    val port =
        raw.substring(colonIndex + 1).toIntOrNull()?.takeIf { it in MIN_VALID_PORT..MAX_VALID_PORT }
            ?: return null
    return ParsedHostHeader(raw.substring(0, colonIndex), port)
}

/** Preview content is already redacted/bounded by the engine, but is arbitrary app data, so it is escaped. */
private fun FilePreviewData.json(): String =
    when (this) {
        is FilePreviewData.Text ->
            "{\"kind\":\"TEXT\",\"content\":\"${content.escapeJson()}\",\"truncated\":$truncated}"
        is FilePreviewData.Binary ->
            "{\"kind\":\"BINARY\",\"sizeBytes\":$sizeBytes}"
        is FilePreviewData.Unavailable ->
            "{\"kind\":\"UNAVAILABLE\",\"reason\":\"${reason.escapeJson()}\"}"
    }

/** Rule ids, hosts, and paths are user-supplied, so every string field is escaped. */
private fun CaptureRule.json(): String =
    "{\"id\":\"${id.escapeJson()}\",\"host\":\"${host.escapeJson()}\"," +
        "\"method\":${method?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"pathPrefix\":${pathPrefix?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"enabled\":$enabled}"

/**
 * Rule ids, hosts, and paths are user-supplied, so every string field is escaped. [stats] comes
 * from [MockEngine.stats] rather than [MockRule] itself -- hit counts live in the engine's side
 * map, not on the immutable rule. A `Delay(StaticResponse)` action reports the wrapped response's
 * status/body/headers plus its own `delayMs`, so the edit dialog can round-trip a delayed rule too.
 */
private fun MockRule.json(stats: MockRuleStats): String {
    val delay = action as? MockAction.Delay
    val staticResponse = (delay?.next ?: action) as? MockAction.StaticResponse
    val (body, bodyTruncated) = staticResponse?.body.orEmpty().truncateForMockRuleJson()
    val headers =
        staticResponse?.headers.orEmpty().entries.joinToString(",") { (name, value) ->
            "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
        }
    val (sourceSnapshot, sourceSnapshotTruncated) =
        sourceBodySnapshot?.truncateForMockRuleJson() ?: (null to false)
    return "{\"id\":\"${id.escapeJson()}\",\"priority\":$priority," +
        "\"method\":${method?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"scheme\":${scheme?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"host\":${host?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"path\":\"${path.escapeJson()}\",\"scope\":\"${scope.name}\"," +
        "\"action\":\"${action::class.simpleName}\"," +
        "\"statusCode\":${staticResponse?.statusCode ?: "null"}," +
        "\"body\":\"${body.escapeJson()}\",\"bodyTruncated\":$bodyTruncated," +
        "\"headers\":{$headers}," +
        "\"delayMs\":${delay?.durationMs ?: "null"}," +
        "\"hitCount\":${stats.hitCount},\"lastHitEpochMs\":${stats.lastHitEpochMs ?: "null"}," +
        "\"enabled\":${persistence.enabled}," +
        "\"sourceBodySnapshot\":${sourceSnapshot?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"sourceBodySnapshotTruncated\":$sourceSnapshotTruncated}"
}

/** Caps a mock rule's serialized response body rather than omitting it outright when oversized. */
private fun String.truncateForMockRuleJson(maxBytes: Int = MOCK_RULE_BODY_JSON_CAP_BYTES): Pair<String, Boolean> {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this to false
    val decoder =
        Charsets.UTF_8.newDecoder().apply {
            onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
            onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE)
        }
    return decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, maxBytes)).toString() to true
}

private const val MOCK_RULE_BODY_JSON_CAP_BYTES = 64 * 1024

private fun pruneStaleExports(
    directory: File,
    nowEpochMs: Long = System.currentTimeMillis(),
): Int =
    directory
        .listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                file.name.startsWith("devconsole-") &&
                file.name.endsWith(".zip") &&
                nowEpochMs - file.lastModified() >= STALE_EXPORT_MAX_AGE_MS
        }.count(File::delete)

private const val STALE_EXPORT_MAX_AGE_MS = 60L * 60L * 1000L

private fun String.escapeJson(): String =
    buildString(length + 16) {
        for (char in this@escapeJson) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

private fun ServerMetadata.json(): String =
    buildString {
        append("{\"protocolVersion\":").append(protocolVersion)
        append(",\"app\":{\"displayName\":\"").append(appDisplayName.escapeJson())
        append("\",\"packageName\":\"").append(appPackageName.escapeJson())
        append("\",\"versionName\":\"").append(appVersionName.escapeJson()).append("\"}")
        append(",\"build\":{\"variant\":\"").append(buildVariant.escapeJson()).append("\"}")
        append(
            ",\"capabilities\":",
        ).append(capabilities.joinToString(prefix = "[", postfix = "]") { "\"${it.escapeJson()}\"" })
        append('}')
    }

private fun ServerMetadata.appJson(): String =
    buildString {
        append("{\"displayName\":\"").append(appDisplayName.escapeJson())
        append("\",\"packageName\":\"").append(appPackageName.escapeJson())
        append("\",\"versionName\":\"").append(appVersionName.escapeJson()).append("\"}")
    }

private fun SdkHealthSnapshot.json(): String =
    "{\"initializationCount\":$initializationCount,\"publishedEventCount\":$publishedEventCount," +
        "\"droppedEventCount\":$droppedEventCount,\"state\":\"${state.escapeJson()}\"," +
        "\"activePrincipalCount\":$activePrincipalCount}"

private fun Timeline.appendFlagOverride(
    browserSessionId: String,
    key: String,
    redactedBefore: String,
    redactedAfter: String,
) {
    val sink = this as? TimelineAppender ?: return
    val previous =
        page(TimelineQuery(limit = 1, sort = TimelineSort.DESC))
            .let { (it as? TimelinePage.Success)?.events?.firstOrNull()?.sequence ?: 0L }
    sink.append(
        io.devconsole.storage.api.StoredEvent(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            sessionId = browserSessionId,
            sequence = previous + 1,
            pluginId = "state",
            type = "flag.override",
            wallTimeMs = System.currentTimeMillis(),
            monoTimeNs = System.nanoTime(),
            severity = 1,
            summary = "Feature flag $key overridden",
            tagsJson = "{\"source\":\"flag\"}",
            payloadJson =
                "{\"key\":\"${key.escapeJson()}\"," +
                    "\"before\":\"${redactedBefore.escapeJson()}\"," +
                    "\"after\":\"${redactedAfter.escapeJson()}\",\"changed\":true}",
        ),
    )
}

/**
 * Everything currently making this session behave differently from a clean install. A bug report
 * filed while a mock or flag override is live is the classic irreproducible bug, so the dashboard
 * surfaces this and [io.devconsole.export] embeds it in every export.
 */
private fun sessionIntegrityJson(
    mockEngine: MockEngine,
    featureFlags: SessionFeatureFlags,
    auditLog: CommandAuditLog,
): String {
    val activeMocks = if (mockEngine.isEnabled()) mockEngine.rules().map { it.id } else emptyList()
    val flagOverrides = featureFlags.overrides()
    val stateMutations = auditLog.events().count { it.commandType.startsWith("state.") }
    val pristine = activeMocks.isEmpty() && flagOverrides.isEmpty() && stateMutations == 0
    val mocksJson = activeMocks.joinToString(",") { "\"${it.escapeJson()}\"" }
    val flagsJson = flagOverrides.entries.joinToString(",") { (key, value) -> "\"${key.escapeJson()}\":$value" }
    return "{\"pristine\":$pristine,\"activeMockRuleIds\":[$mocksJson]," +
        "\"featureFlagOverrides\":{$flagsJson},\"stateMutationCount\":$stateMutations}"
}

/** One timeline event as it appears inside a bug report bundle. Already redacted upstream. */
private fun io.devconsole.storage.api.StoredEvent.reportJson(): String =
    buildString {
        append("{\"id\":\"").append(id.escapeJson())
        append("\",\"pluginId\":\"").append(pluginId.escapeJson())
        append("\",\"type\":\"").append(type.escapeJson())
        append("\",\"severity\":").append(severity)
        append(",\"wallTimeMs\":").append(wallTimeMs)
        append(",\"summary\":\"").append(summary.escapeJson()).append("\"")
        append(",\"tags\":").append(tagsJson)
        payloadJson?.let { append(",\"payload\":").append(it) }
        append('}')
    }

private fun String.hostOrEmpty(): String =
    runCatching {
        java.net
            .URI(this)
            .host
            .orEmpty()
    }.getOrDefault("")

/**
 * Matches only canonical base64: full 4-character groups, with padding permitted just at the end.
 */
private val BASE64_BODY_PATTERN = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

/**
 * `java.util.Base64` is API 26 and this module's `minSdk` is 23, so decoding through it crashes on
 * Android 7 the first time the composer sends a binary body. Ktor's decoder is pure Kotlin and
 * available at every API level, but it recovers from malformed input rather than rejecting it, so
 * the shape is checked first — bad input has to fail the request, as it did before, instead of
 * silently decoding to something the caller never sent.
 */
private fun decodeComposerBinaryBody(encoded: String): ByteArray {
    require(encoded.length % 4 == 0 && BASE64_BODY_PATTERN.matches(encoded)) {
        "binaryBodyBase64 is not valid base64"
    }
    return encoded.decodeBase64Bytes()
}

private fun io.ktor.http.Parameters.toComposerRequestOrNull(): ComposerRequest? =
    runCatching {
        require(
            entries().sumOf { (name, values) -> name.length + values.sumOf(String::length) } <= MAX_COMPOSER_FORM_CHARS,
        )
        val method = get("method")?.uppercase().orEmpty()
        val url = get("url").orEmpty()
        require(method.length <= 16 && url.length <= MAX_COMPOSER_URL_CHARS)
        val headers =
            getAll("header")
                .orEmpty()
                .also { require(it.size <= MAX_COMPOSER_FIELDS) }
                .map { it.splitComposerPair(':') }
                .toMap()
                .also { values ->
                    require(values.all { (name, value) -> name.length <= 256 && value.length <= 16 * 1024 })
                }
        val query =
            getAll("query")
                .orEmpty()
                .also { require(it.size <= MAX_COMPOSER_FIELDS) }
                .map { it.splitComposerPair('=').let { (name, value) -> ComposerQueryParameter(name, value) } }
        val formFields =
            getAll("form")
                .orEmpty()
                .also { require(it.size <= MAX_COMPOSER_FIELDS) }
                .map { it.splitComposerPair('=') }
                .toMap()
        val multipart =
            getAll("multipart")
                .orEmpty()
                .also { require(it.size <= MAX_COMPOSER_FIELDS) }
                .map { it.splitComposerPair('=').let { (name, value) -> ComposerMultipartPart(name, value) } }
        val variables =
            buildList {
                getAll("variable")
                    .orEmpty()
                    .forEach { encoded ->
                        val (name, value) = encoded.splitComposerPair('=')
                        add(ComposerVariable(name, value))
                    }
                getAll("secretVariable")
                    .orEmpty()
                    .forEach { encoded ->
                        val (name, value) = encoded.splitComposerPair('=')
                        add(ComposerVariable(name, value, secret = true))
                    }
            }.also {
                require(
                    it.size <= MAX_COMPOSER_FIELDS && it.map(ComposerVariable::name).distinct().size == it.size,
                )
            }
        val body = get("body")?.also { require(it.length <= MAX_COMPOSER_BODY_BYTES) }
        val bodyType =
            get("bodyType")
                ?.let { ComposerBodyType.valueOf(it.uppercase()) }
                ?: if (body == null) ComposerBodyType.NONE else ComposerBodyType.TEXT
        val binaryBytes =
            get("binaryBodyBase64")?.let { encoded ->
                require(encoded.length <= MAX_COMPOSER_BINARY_BASE64_CHARS)
                decodeComposerBinaryBody(encoded).also { require(it.size <= MAX_COMPOSER_BODY_BYTES) }
            }
        val binaryBody =
            binaryBytes?.let {
                ComposerBinaryBody(
                    fileName = get("binaryFileName")?.takeIf(String::isNotBlank) ?: "request.bin",
                    contentType = get("binaryContentType")?.takeIf(String::isNotBlank) ?: "application/octet-stream",
                    bytes = it,
                )
            }
        val timeoutMs = get("timeoutMs")?.toIntOrNull() ?: DEFAULT_COMPOSER_TIMEOUT_MS
        require(timeoutMs in 1..MAX_COMPOSER_TIMEOUT_MS)
        val followRedirects =
            get("followRedirects")?.let {
                when (it.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> error("Invalid redirect policy")
                }
            } ?: true
        require(bodyType != ComposerBodyType.BINARY_FILE || binaryBody != null)
        require(bodyType != ComposerBodyType.MULTIPART || multipart.isNotEmpty())
        require(bodyType != ComposerBodyType.FORM_URL_ENCODED || formFields.isNotEmpty())
        ComposerRequest(
            method = method,
            url = url,
            headers = headers,
            body = body,
            query = query,
            bodyType = bodyType,
            formFields = formFields,
            multipartParts = multipart,
            binaryBody = binaryBody,
            timeoutMs = timeoutMs,
            followRedirects = followRedirects,
            variables = variables,
        ).also(ComposerRequest::resolve)
    }.getOrNull()

private fun String.splitComposerPair(separator: Char): Pair<String, String> {
    val index = indexOf(separator)
    require(index > 0)
    val name = substring(0, index).trim()
    val value = substring(index + 1).trim()
    require(name.isNotBlank() && name.length <= MAX_COMPOSER_FIELD_CHARS && value.length <= MAX_COMPOSER_FIELD_CHARS)
    return name to value
}

private fun ComposerRequest.secretValues(): List<String> =
    variables
        .asSequence()
        .filter(ComposerVariable::secret)
        .map(ComposerVariable::value)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

private fun recordComposerCapture(
    request: ResolvedComposerRequest,
    secretValues: List<String>,
    response: ComposerResponse?,
    error: Throwable?,
    correlationId: String,
    browserSessionId: String,
    startedAtEpochMs: Long,
    networkTransactions: NetworkTransactionStore,
    timeline: Timeline,
    redaction: RedactionEngine,
) {
    val replacement = redaction.replacement()
    // The human-readable marker contains angle brackets, which are not legal in a raw URI.
    // Percent-encode it before the capture factory parses the URL.
    val encodedReplacement =
        java.net.URLEncoder
            .encode(replacement, Charsets.UTF_8.name())
            .replace("+", "%20")
    val safeUrl = request.url.redactComposerSecrets(secretValues, encodedReplacement)
    val requestBody =
        when (request.bodyType) {
            ComposerBodyType.NONE -> null
            ComposerBodyType.TEXT, ComposerBodyType.JSON, ComposerBodyType.FORM_URL_ENCODED ->
                request.body
                    ?.redactComposerSecrets(secretValues, replacement)
                    ?.encodeToByteArray()
            ComposerBodyType.MULTIPART ->
                request.multipartParts
                    .joinToString("&") { "${it.name}=${it.value}" }
                    .redactComposerSecrets(secretValues, replacement)
                    .encodeToByteArray()
            ComposerBodyType.BINARY_FILE -> request.binaryBody?.bytes
        }
    val requestContentType =
        request.headers.contentTypeOrNull()
            ?: when (request.bodyType) {
                ComposerBodyType.NONE -> null
                ComposerBodyType.TEXT -> "text/plain"
                ComposerBodyType.JSON -> "application/json"
                ComposerBodyType.FORM_URL_ENCODED -> "application/x-www-form-urlencoded"
                ComposerBodyType.MULTIPART -> "multipart/form-data"
                ComposerBodyType.BINARY_FILE -> request.binaryBody?.contentType
            }
    val safeResponseBody =
        response
            ?.body
            ?.redactComposerSecrets(secretValues, replacement)
            ?.encodeToByteArray()
    val capture =
        NetworkCaptureFactory(redaction).capture(
            NetworkRequestInput(
                method = request.method,
                url = safeUrl,
                headers =
                    request.headers.mapValues { (_, value) ->
                        value.redactComposerSecrets(secretValues, replacement)
                    },
                body = requestBody,
                contentType = requestContentType,
                correlationId = correlationId,
                pluginId = "composer",
            ).withMetadata(
                NetworkRequestMetadata(
                    threadName = Thread.currentThread().name,
                    bodyLength =
                        requestBody?.size?.toLong()
                            ?: request.binaryBody
                                ?.bytes
                                ?.size
                                ?.toLong(),
                    tags = mapOf("source" to "composer"),
                ),
            ),
            NetworkResponseInput(
                statusCode = response?.statusCode ?: 0,
                headers =
                    response
                        ?.headers
                        .orEmpty()
                        .mapValues { (_, value) -> value.redactComposerSecrets(secretValues, replacement) },
                body = safeResponseBody,
                contentType = response?.headers?.contentTypeOrNull(),
                error = error?.message ?: error?.javaClass?.simpleName,
            ).withMetadata(
                NetworkResponseMetadata(
                    bodyLength = safeResponseBody?.size?.toLong(),
                    timings = NetworkTimingPhases(waitMs = response?.durationMs),
                    exceptionClass = error?.javaClass?.name,
                ),
            ),
        )
    val transaction =
        NetworkTransaction(
            id = UUID.randomUUID().toString(),
            startedAtEpochMs = startedAtEpochMs,
            completedAtEpochMs = System.currentTimeMillis(),
            capture = capture,
        )
    runCatching { networkTransactions.record(transaction) }
    runCatching { timeline.appendComposerExecution(browserSessionId, transaction) }
}

private fun Map<String, String>.contentTypeOrNull(): String? =
    entries.firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }?.value

private fun String.redactComposerSecrets(
    secretValues: List<String>,
    replacement: String,
): String = secretValues.fold(this) { current, secret -> current.replace(secret, replacement) }

private fun Timeline.appendComposerExecution(
    browserSessionId: String,
    transaction: NetworkTransaction,
) {
    val sink = this as? TimelineAppender ?: return
    val previous =
        page(TimelineQuery(limit = 1, sort = TimelineSort.DESC))
            .let { (it as? TimelinePage.Success)?.events?.firstOrNull()?.sequence ?: 0L }
    val request = transaction.capture.request
    val response = transaction.capture.response
    val failed = response?.error != null || response?.statusCode == 0
    sink.append(
        io.devconsole.storage.api.StoredEvent(
            id = UUID.randomUUID().toString(),
            sessionId = browserSessionId,
            sequence = previous + 1,
            pluginId = "composer",
            type = if (failed) "http.request.failed" else "http.response.completed",
            wallTimeMs = transaction.completedAtEpochMs ?: transaction.startedAtEpochMs,
            monoTimeNs = System.nanoTime(),
            severity = if (failed) 3 else 1,
            summary =
                "${request.method} ${request.url.path} " +
                    if (failed) "failed" else response?.statusCode.toString(),
            correlationId = request.correlationId,
            tagsJson = "{\"source\":\"composer\"}",
            payloadJson = "{\"networkTransactionId\":\"${transaction.id.escapeJson()}\"}",
        ),
    )
}

private const val DEFAULT_COMPOSER_TIMEOUT_MS = 15_000
private const val MAX_COMPOSER_TIMEOUT_MS = 120_000
private const val MAX_COMPOSER_FIELDS = 100
private const val MAX_COMPOSER_FIELD_CHARS = 16 * 1024
private const val MAX_COMPOSER_URL_CHARS = 64 * 1024
private const val MAX_COMPOSER_BODY_BYTES = 2 * 1024 * 1024
private const val MAX_COMPOSER_BINARY_BASE64_CHARS = 3 * 1024 * 1024
private const val MAX_COMPOSER_FORM_CHARS = 3 * 1024 * 1024

/** Drops any query string before the URL reaches the audit log, which is readable over HTTP. */
private fun String.withoutQuery(): String = substringBefore('?')

/** Composer is a device-side proxy, so every destination must be explicitly allowlisted. */
private fun Set<String>.permitsHostOf(url: String): Boolean {
    val host = url.hostOrEmpty().lowercase()
    return host.isNotEmpty() && any { it.lowercase() == host }
}

private fun String.isHttpUrl(): Boolean =
    runCatching {
        val uri = java.net.URI(this)
        uri.isAbsolute && uri.host != null && uri.scheme.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

/**
 * [NetworkExportResolution.transactions] is what actually gets rendered into the HAR/Postman body;
 * [truncated] is surfaced to the caller as response headers (see [applyExportTruncationHeaders])
 * rather than folded into the body, so the exported file itself stays a byte-for-byte valid HAR/
 * Postman document regardless of whether the selection was cut down to fit
 * [NetworkTransactionQuery.MAX_PAGE_LIMIT].
 *
 * Bound decision: this reuses the store's existing 500-row page bound (unchanged, not raised) --
 * raising it would mean holding more than 500 fully-rendered transaction bodies in memory per
 * export request, which is the same risk the page endpoint was bounded against in the first place.
 * Instead of silently dropping the overflow, every caller now learns about it: [truncated] is `true`
 * when an id-based selection had ids that resolved to nothing (typically because the caller's
 * candidate set -- e.g. "everything matching this filter" computed client-side -- exceeded the
 * bound before ever reaching this endpoint) or when a filter-based selection had more matches than
 * fit in one page.
 */
private data class NetworkExportResolution(
    val transactions: List<NetworkTransaction>,
    val truncated: Boolean,
)

/**
 * Resolves which transactions a HAR or Postman export should include, shared by all four routes
 * (`GET`/`POST` x `har`/`postman`) so the formats and verbs can never select a different set of rows
 * for what a caller intends to be the same export: explicit ids take priority (an exact
 * [ExportSelection.Ids] match, same as the Android exporter's transaction multi-select) -- from
 * [bodyIds] when the caller is the `POST` route (a form-body id list, for a selection too large to
 * safely fit a GET query string), otherwise from the `id` query parameter, same as before -- else
 * every transaction matching the full [networkTransactionQuery] filter set ([ExportSelection.All]),
 * which is how "select all matching the current filter" (no explicit ids) stays expressible on
 * either verb using the exact same filter params the transaction list endpoint accepts. Responds
 * `VALIDATION_FAILED` and returns null on any invalid input or tampered cursor; callers must
 * `return@get`/`return@post` when this returns null.
 */
@Suppress("ReturnCount") // One early-exit per validation/selection-branch reads clearest.
private suspend fun io.ktor.server.application.ApplicationCall.resolveNetworkExportTransactions(
    networkTransactions: NetworkTransactionStore,
    bodyIds: List<String>? = null,
): NetworkExportResolution? {
    val selectedIds = (bodyIds ?: request.queryParameters.getAll("id").orEmpty()).distinct()
    if (selectedIds.size > NetworkTransactionQuery.MAX_PAGE_LIMIT || selectedIds.any(String::isBlank)) {
        respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
        return null
    }
    val query =
        networkTransactionQuery(defaultLimit = NetworkTransactionQuery.MAX_PAGE_LIMIT) ?: run {
            respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
            return null
        }
    if (selectedIds.isNotEmpty()) {
        val resolved =
            networkTransactions
                .resolveExportSelection(ExportSelection.Ids(selectedIds.toSet()), query)
                .orEmpty()
        return NetworkExportResolution(resolved, truncated = resolved.size < selectedIds.size)
    }
    val page = networkTransactions.page(query)
    if (page.invalidCursor) {
        respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
        return null
    }
    return NetworkExportResolution(page.transactions, truncated = page.hasMore)
}

/** Machine-readable companions to the HAR/Postman body: never inline these into the JSON payload
 * itself (see [NetworkExportResolution]'s KDoc for why). */
private fun io.ktor.server.application.ApplicationCall.applyExportTruncationHeaders(
    resolution: NetworkExportResolution,
) {
    response.headers.append("X-DevConsole-Export-Count", resolution.transactions.size.toString())
    response.headers.append("X-DevConsole-Export-Truncated", resolution.truncated.toString())
    response.headers.append("X-DevConsole-Export-Limit", NetworkTransactionQuery.MAX_PAGE_LIMIT.toString())
}

/**
 * Shared bearer+Origin+CSRF gate for `POST /api/v1/network/har` and `POST /api/v1/network/postman`,
 * matching `POST /api/v1/exports`'s gating precisely (see that route above) since this is the same
 * shape of request: a mutation-shaped POST (chosen over GET here only to carry a large id list in
 * the body) that nonetheless just reads already-redacted data. Consumes the request body (`id` form
 * parameters) and returns the resolved selection, or `null` after already responding once the
 * caller should `return@post`.
 */
@Suppress("ReturnCount") // One early-exit per gate stage (auth, CSRF) reads clearest.
private suspend fun io.ktor.server.application.ApplicationCall.authorizeNetworkExportPost(
    networkTransactions: NetworkTransactionStore,
    sessionAuthority: SessionAuthority,
    commandAuditLog: CommandAuditLog,
): NetworkExportResolution? {
    val session = sessionAuthority.bearerSession(request.headers[HttpHeaders.Authorization])
    if (session == null) {
        respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
        return null
    }
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    if (request.headers[HttpHeaders.Origin] != expectedOrigin || request.headers[CSRF_HEADER] != session.csrfToken) {
        commandAuditLog.recordExport(session.id, CommandAuditResult.REJECTED, "csrf")
        respondText("{\"code\":\"CSRF_INVALID\"}", status = HttpStatusCode.Forbidden)
        return null
    }
    val ids = receiveParameters().getAll("id").orEmpty()
    return resolveNetworkExportTransactions(networkTransactions, bodyIds = ids)
}

/** Answers a malformed request with the JSON envelope at a 4xx. Debug-logged: rejections are routine. */
private suspend fun io.ktor.server.application.ApplicationCall.respondClientError(
    status: HttpStatusCode,
    code: String,
    cause: Throwable,
) {
    application.environment.log.debug(
        "Rejected ${request.httpMethod.value} ${request.uri} as $code: ${cause.message}",
    )
    if (!response.isCommitted) {
        respondText("{\"code\":\"$code\"}", status = status, contentType = ContentType.Application.Json)
    }
}

private fun io.ktor.server.application.ApplicationCall.networkTransactionQuery(
    defaultLimit: Int = NetworkTransactionQuery.DEFAULT_PAGE_LIMIT,
): NetworkTransactionQuery? {
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: defaultLimit
    if (limit !in 1..NetworkTransactionQuery.MAX_PAGE_LIMIT) return null
    val rawStatuses = request.queryParameters.getAll("status").orEmpty()
    val statuses = rawStatuses.map(String::toIntOrNull)
    if (statuses.any { it == null }) return null
    val fromEpochMs =
        request.queryParameters.strictLong("from") ?: if ("from" in request.queryParameters) return null else null
    val toEpochMs =
        request.queryParameters.strictLong("to") ?: if ("to" in request.queryParameters) return null else null
    val minDurationMs =
        request.queryParameters.strictLong("minDurationMs")
            ?: if ("minDurationMs" in request.queryParameters) return null else null
    val maxDurationMs =
        request.queryParameters.strictLong("maxDurationMs")
            ?: if ("maxDurationMs" in request.queryParameters) return null else null
    val statusFrom =
        request.queryParameters.strictInt("statusFrom")
            ?: if ("statusFrom" in request.queryParameters) return null else null
    val statusTo =
        request.queryParameters.strictInt("statusTo")
            ?: if ("statusTo" in request.queryParameters) return null else null
    val hasError =
        request.queryParameters["error"]?.let { value ->
            when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> return null
            }
        }
    if (fromEpochMs != null && toEpochMs != null && fromEpochMs > toEpochMs) return null
    if (minDurationMs != null && maxDurationMs != null && minDurationMs > maxDurationMs) return null
    if (listOfNotNull(fromEpochMs, toEpochMs, minDurationMs, maxDurationMs).any { it < 0 }) return null
    if (listOfNotNull(statusFrom, statusTo).any { it !in 100..999 }) return null
    val tags =
        request.queryParameters
            .getAll("tag")
            .orEmpty()
            .map { encoded ->
                val separator = encoded.indexOf('=')
                if (separator <= 0 || separator == encoded.lastIndex) return null
                encoded.substring(0, separator) to encoded.substring(separator + 1)
            }.toMap()
    return NetworkTransactionQuery(
        limit = limit,
        cursor = request.queryParameters["cursor"],
        methods =
            request.queryParameters
                .getAll("method")
                ?.toSet()
                .orEmpty(),
        hosts =
            request.queryParameters
                .getAll("host")
                ?.toSet()
                .orEmpty(),
        statuses = statuses.filterNotNull().toSet(),
        correlationId = request.queryParameters["correlationId"],
    ).withFilters(
        NetworkTransactionFilters(
            fromEpochMs = fromEpochMs,
            toEpochMs = toEpochMs,
            paths =
                request.queryParameters
                    .getAll("path")
                    ?.toSet()
                    .orEmpty(),
            contentTypes =
                request.queryParameters
                    .getAll("contentType")
                    ?.toSet()
                    .orEmpty(),
            minDurationMs = minDurationMs,
            maxDurationMs = maxDurationMs,
            statusFrom = statusFrom,
            statusTo = statusTo,
            hasError = hasError,
            tags = tags,
            query = request.queryParameters["query"],
        ),
    )
}

private fun io.ktor.http.Parameters.strictLong(name: String): Long? = this[name]?.toLongOrNull()

private fun io.ktor.http.Parameters.strictInt(name: String): Int? = this[name]?.toIntOrNull()

// An unrecognized or absent `protocol` query value means "no filtering" -- unlike
// SocketProtocol.fromWireName's own fail-open-to-WEBSOCKET default, an unset filter here must not
// silently exclude every MQTT connection/message from an older or misbehaving client.
private fun io.ktor.server.application.ApplicationCall.socketProtocolFilter(): SocketProtocol? =
    when (request.queryParameters["protocol"]) {
        SocketProtocol.WEBSOCKET.wireName -> SocketProtocol.WEBSOCKET
        SocketProtocol.MQTT.wireName -> SocketProtocol.MQTT
        else -> null
    }

private fun io.ktor.server.application.ApplicationCall.socketMessageQuery(): SocketMessageQuery? {
    val directions =
        request.queryParameters
            .getAll("direction")
            .orEmpty()
            .map { runCatching { SocketDirection.valueOf(it.uppercase()) }.getOrNull() }
    if (directions.any { it == null }) return null
    val frameTypes =
        request.queryParameters
            .getAll("frameType")
            .orEmpty()
            .map { runCatching { SocketFrameType.valueOf(it.uppercase()) }.getOrNull() }
    if (frameTypes.any { it == null }) return null
    val fromEpochMs =
        request.queryParameters.strictLong("from")
            ?: if ("from" in request.queryParameters) return null else null
    val toEpochMs =
        request.queryParameters.strictLong("to")
            ?: if ("to" in request.queryParameters) return null else null
    if (fromEpochMs != null && toEpochMs != null && fromEpochMs > toEpochMs) return null
    if (listOfNotNull(fromEpochMs, toEpochMs).any { it < 0 }) return null
    val hasError =
        request.queryParameters["error"]?.let {
            when (it.lowercase()) {
                "true" -> true
                "false" -> false
                else -> return null
            }
        }
    return SocketMessageQuery(
        connectionIds =
            request.queryParameters
                .getAll("connectionId")
                ?.toSet()
                .orEmpty(),
        directions = directions.filterNotNull().toSet(),
        frameTypes = frameTypes.filterNotNull().toSet(),
        fromEpochMs = fromEpochMs,
        toEpochMs = toEpochMs,
        query = request.queryParameters["query"],
        hasError = hasError,
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondNetworkTransactionPage(
    page: NetworkTransactionPage,
) {
    if (page.invalidCursor) {
        respondText("{\"code\":\"VALIDATION_FAILED\"}", status = HttpStatusCode.BadRequest)
        return
    }
    val data = page.transactions.joinToString(",") { it.summaryJson() }
    val cursor = page.nextCursor?.let { "\"${it.escapeJson()}\"" } ?: "null"
    respondText(
        "{\"data\":[$data],\"page\":{\"nextCursor\":$cursor,\"hasMore\":${page.hasMore}}}",
        contentType = io.ktor.http.ContentType.Application.Json,
    )
}

private fun NetworkTransaction.summaryJson(): String {
    val correlationId = capture.request.correlationId?.let { "\"${it.escapeJson()}\"" } ?: "null"
    val tags =
        capture.request.metadata.tags
            .jsonObject()
    val error = capture.response?.error?.let { "\"${it.escapeJson()}\"" } ?: "null"
    val contentType = (capture.response?.contentType ?: capture.request.contentType).orEmpty().escapeJson()
    return "{\"id\":\"${id.escapeJson()}\",\"startedAtEpochMs\":$startedAtEpochMs," +
        "\"completedAtEpochMs\":${completedAtEpochMs ?: "null"},\"durationMs\":${durationMs ?: "null"}," +
        "\"method\":\"${capture.request.method.escapeJson()}\"," +
        "\"host\":\"${capture.request.url.host.escapeJson()}\"," +
        "\"path\":\"${capture.request.url.path.escapeJson()}\",\"status\":${capture.response?.statusCode ?: "null"}," +
        "\"contentType\":\"$contentType\"," +
        "\"error\":$error,\"tags\":$tags,\"correlationId\":$correlationId}"
}

// This detail JSON's request/response attachmentId never needs a redactionApplicability sibling:
// the sole writer of a network-body attachment is PlatformFacadeProvider.persistNetworkAttachment,
// which always calls AttachmentWriteRequest with isRedacted = true and the defaulted
// redactionApplicability = APPLIED -- a network capture is never a screenshot. Unlike the timeline
// ("screenshot.captured" events) and evidence tray, there is no code path here that could ever
// produce NOT_APPLICABLE, so reporting it would be a lookup with only one possible answer.
private fun NetworkTransaction.detailJson(): String =
    buildString {
        append(summaryJson().dropLast(1))
        append(",\"request\":{\"url\":\"")
            .append(
                capture.request.url.display
                    .escapeJson(),
            ).append("\",\"headers\":")
        append(capture.request.headers.jsonHeaders())
        append(",\"contentType\":").append(capture.request.contentType.jsonStringOrNull())
        append(",\"body\":").append(capture.request.body.detailJson())
        append(",\"bodyMetadata\":").append(
            capture.request.metadata.body
                .detailJson(),
        )
        append(",\"attachmentId\":").append(capture.request.attachmentId.jsonStringOrNull())
        append("},\"response\":")
        val response = capture.response
        if (response == null) {
            append("null")
        } else {
            append("{\"headers\":").append(response.headers.jsonHeaders())
            append(",\"contentType\":").append(response.contentType.jsonStringOrNull())
            append(",\"body\":").append(response.body.detailJson())
            append(",\"bodyMetadata\":").append(response.metadata.body.detailJson())
            append(",\"attachmentId\":").append(response.attachmentId.jsonStringOrNull())
            append(",\"timings\":").append(response.metadata.timings.detailJson())
            append('}')
        }
        append('}')
    }

private fun BodyPreview.detailJson(): String =
    when (this) {
        is BodyPreview.Text ->
            "{\"type\":\"text\",\"value\":\"${value.escapeJson()}\",\"truncated\":$truncated}"
        is BodyPreview.Binary ->
            "{\"type\":\"binary\",\"length\":$length,\"truncated\":$truncated}"
        BodyPreview.Absent -> "{\"type\":\"absent\"}"
    }

private fun CaptureBodyMetadata.detailJson(): String =
    "{\"declaredLength\":${declaredLength ?: "null"},\"capturedBytes\":$capturedBytes," +
        "\"truncated\":$truncated,\"omittedReason\":${omittedReason.jsonStringOrNull()}}"

/**
 * Each phase is legitimately absent (pooled connection skips DNS/connect, plaintext skips TLS,
 * a cached response does no network work at all) — emit JSON `null`, never a fabricated `0`.
 */
private fun NetworkTimingPhases.detailJson(): String =
    "{\"dnsMs\":${dnsMs ?: "null"},\"connectMs\":${connectMs ?: "null"},\"tlsMs\":${tlsMs ?: "null"}," +
        "\"sendMs\":${sendMs ?: "null"},\"waitMs\":${waitMs ?: "null"},\"receiveMs\":${receiveMs ?: "null"}}"

private fun String?.jsonStringOrNull(): String = this?.let { "\"${it.escapeJson()}\"" } ?: "null"

private fun StoredEvent.relatedEventJson(): String =
    "{\"id\":\"${id.escapeJson()}\",\"sequence\":$sequence,\"wallTimeMs\":$wallTimeMs," +
        "\"monotonicNanos\":$monoTimeNs,\"pluginId\":\"${pluginId.escapeJson()}\"," +
        "\"type\":\"${type.escapeJson()}\",\"severity\":$severity,\"summary\":\"${summary.escapeJson()}\"," +
        "\"correlationId\":${correlationId?.let { "\"${it.escapeJson()}\"" } ?: "null"}}"

/**
 * Payload is embedded as JSON only after capture-time redaction; no reparsing means malformed legacy
 * data remains harmless. `redactionApplicability` is a live, authoritative lookup (never inferred
 * from `type`/`pluginId`) -- a "screenshot.captured" event's attachment is NOT_APPLICABLE by
 * construction, but nothing here should have to know that rule to report it correctly.
 */
private suspend fun StoredEvent.retainedJson(attachmentMetadataReader: suspend (String) -> StoredAttachment?): String {
    val applicability = attachmentId?.let { attachmentMetadataReader(it)?.redactionApplicability }
    return "{\"id\":\"${id.escapeJson()}\",\"sessionId\":\"${sessionId.escapeJson()}\"," +
        "\"sequence\":$sequence,\"wallTimeMs\":$wallTimeMs,\"pluginId\":\"${pluginId.escapeJson()}\"," +
        "\"type\":\"${type.escapeJson()}\",\"severity\":$severity,\"summary\":\"${summary.escapeJson()}\"," +
        "\"correlationId\":${correlationId.jsonStringOrNull()},\"tagsJson\":${tagsJson.jsonStringOrNull()}," +
        "\"payloadJson\":${payloadJson.jsonStringOrNull()},\"attachmentId\":${attachmentId.jsonStringOrNull()}," +
        "\"redactionApplicability\":${applicability?.let { "\"${it.name}\"" } ?: "null"}}"
}

private fun Map<String, String>.jsonHeaders(): String =
    entries.joinToString(prefix = "[", postfix = "]") { (name, value) ->
        "{\"name\":\"${name.escapeJson()}\",\"value\":\"${value.escapeJson()}\"}"
    }

private fun Map<String, String>.jsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
        "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
    }

private fun PushEvent.json(): String =
    buildString {
        append("{\"provider\":\"").append(provider.escapeJson()).append("\"")
        append(",\"messageId\":").append(messageId?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append(",\"source\":").append(source?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append(",\"sentAtEpochMs\":").append(sentAtEpochMs ?: "null")
        append(",\"receivedAtEpochMs\":").append(receivedAtEpochMs)
        append(",\"lifecycle\":\"").append(lifecycle.name).append("\"")
        append(",\"simulated\":").append(simulated)
        append(",\"data\":").append(data.jsonObject())
        append(",\"rawMetadata\":").append(rawMetadata.jsonObject())
        append(",\"notification\":")
        append(
            notification?.let { notification ->
                "{\"title\":${notification.title?.let {
                    "\"${it.escapeJson()}\""
                } ?: "null"},\"body\":${notification.body?.let {
                    "\"${it.escapeJson()}\""
                } ?: "null"},\"channelId\":${notification.channelId?.let {
                    "\"${it.escapeJson()}\""
                } ?: "null"},\"imageUrl\":${notification.imageUrl?.let { "\"${it.escapeJson()}\"" } ?: "null"}}"
            } ?: "null",
        )
        append('}')
    }

private fun SocketConnection.summaryJson(): String {
    val errorJson = error?.let { "\"${it.escapeJson()}\"" } ?: "null"
    return "{\"id\":\"${id.escapeJson()}\",\"url\":\"${url.escapeJson()}\",\"state\":\"${state.name}\"," +
        "\"openedAtEpochMs\":$openedAtEpochMs,\"closedAtEpochMs\":${closedAtEpochMs ?: "null"}," +
        "\"sentCount\":$sentCount,\"receivedCount\":$receivedCount,\"error\":$errorJson," +
        "\"protocol\":\"${protocol.wireName.escapeJson()}\"}"
}

private fun SocketConnection.detailJson(): String =
    summaryJson().dropLast(1) +
        ",\"reconnectAttempt\":$reconnectAttempt," +
        "\"lifecycle\":[${lifecycleEvents.joinToString(",") { it.json() }}]," +
        "\"messages\":[${messages.joinToString(",") { it.json() }}]}"

private fun SocketMessage.json(): String {
    val contentTypeJson = contentType?.let { "\"${it.escapeJson()}\"" } ?: "null"
    val topicJson = MqttFrameMetadata.topic(contentType)?.let { "\"${it.escapeJson()}\"" } ?: "null"
    val qosJson = MqttFrameMetadata.qos(contentType)?.toString() ?: "null"
    return "{\"connectionId\":\"${connectionId.escapeJson()}\",\"direction\":\"${direction.name}\"," +
        "\"timestampEpochMs\":$timestampEpochMs,\"frameType\":\"${metadata.frameType.name}\"," +
        "\"textFormat\":\"${metadata.textFormat.name}\",\"contentType\":$contentTypeJson," +
        "\"topic\":$topicJson,\"qos\":$qosJson,\"payload\":${payload.json()}}"
}

private fun SocketLifecycleEvent.json(): String =
    "{\"connectionId\":\"${connectionId.escapeJson()}\",\"type\":\"${type.name}\"," +
        "\"timestampEpochMs\":$timestampEpochMs,\"code\":${code ?: "null"}," +
        "\"reason\":${reason?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"error\":${error?.let { "\"${it.escapeJson()}\"" } ?: "null"}}"

private fun SocketPayload.json(): String =
    when (this) {
        is SocketPayload.Text -> "{\"kind\":\"text\",\"preview\":\"${preview.escapeJson()}\",\"truncated\":$truncated}"
        is SocketPayload.Binary ->
            "{\"kind\":\"binary\",\"length\":$length,\"truncated\":$truncated," +
                "\"preview\":${preview?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
                "\"previewEncoding\":${previewEncoding?.let { "\"${it.name}\"" } ?: "null"}}"
    }

/** [StateMutator.inputSchema] is host-authored JSON Schema, not arbitrary app data, so it is embedded as-is. */
private fun StateMutator.json(): String = "{\"id\":\"${id.escapeJson()}\",\"inputSchema\":$inputSchema}"

private fun List<StateMutator>.json(): String = joinToString(prefix = "[", postfix = "]") { it.json() }

private fun StateSnapshot.json(): String =
    values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.escapeJson()}\":${value.json()}"
    }

private fun StateValue.json(): String =
    when (this) {
        StateValue.Null -> "null"
        is StateValue.BooleanValue -> value.toString()
        is StateValue.NumberValue ->
            value.toDouble().takeIf(Double::isFinite)?.toString()
                ?: "{\"kind\":\"unavailable\",\"reason\":\"non-finite number\"}"
        is StateValue.StringValue -> "\"${value.escapeJson()}\""
        is StateValue.ObjectValue ->
            values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "\"${key.escapeJson()}\":${value.json()}"
            }
        is StateValue.ArrayValue -> values.joinToString(prefix = "[", postfix = "]") { it.json() }
        StateValue.Redacted -> "{\"kind\":\"redacted\"}"
        is StateValue.Unavailable -> "{\"kind\":\"unavailable\",\"reason\":${reason?.let {
            "\"${it.escapeJson()}\""
        } ?: "null"}}"
        is StateValue.BinaryMetadata ->
            "{\"kind\":\"binary\",\"byteLength\":$byteLength,\"mediaType\":${mediaType?.let {
                "\"${it.escapeJson()}\""
            } ?: "null"}}"
    }

private fun io.ktor.server.application.ApplicationCall.isReadMutationAuthorized(authority: SessionAuthority): Boolean {
    val session = authority.bearerSession(request.headers[HttpHeaders.Authorization]) ?: return false
    val expectedOrigin = "http://${request.headers[HttpHeaders.Host].orEmpty()}"
    return request.headers[HttpHeaders.Origin] == expectedOrigin && request.headers[CSRF_HEADER] == session.csrfToken
}

// ============================================================================================
// Evidence snapshot materialization (A -- see the design spec's "Snapshot at flag time" section).
//
// The defect this exists to fix: the dashboard used to re-derive a flagged item from whatever list
// it still happened to hold, so a flagged network transaction degraded to a bare label once the list
// paged past it. The fix is that POST /api/v1/evidence materializes the subject exactly once, here,
// from the same already-redacted sources the detail endpoints use, and the result is stored verbatim
// in StoredEvidenceItem.snapshotJson -- never re-derived on a later read.
//
// A field that is genuinely unavailable (a screenshot's byte count is never persisted anywhere this
// route can read it back from; a network item's own attachmentId is embedded inside its snapshot
// rather than duplicated at the StoredEvidenceItem level) is omitted, not defaulted -- the same
// honesty rule the rest of this codebase already follows for redacted/unavailable data.
// ============================================================================================

// Plugin ids a StoredEvent must carry to be honestly flagged under the matching EvidenceKind -- see
// PlatformFacadeProvider's screenshot capture and CrashCapture.PLUGIN_ID on the device side.
private const val SCREENSHOT_PLUGIN_ID = "screenshot"
private const val CRASH_PLUGIN_ID = "crash"

/** One materialized evidence subject, ready to become a [StoredEvidenceItem]. */
private data class MaterializedEvidenceSubject(
    val label: String,
    val snapshotJson: String,
    val attachmentId: String?,
)

@Suppress("LongParameterList") // Every capture source a flaggable subject can come from is threaded through.
private fun materializeEvidenceSubject(
    kind: EvidenceKind,
    subjectId: String,
    timeline: Timeline,
    networkTransactions: NetworkTransactionStore,
    socketStore: SocketStore,
    pushStore: PushStore,
): MaterializedEvidenceSubject? =
    when (kind) {
        EvidenceKind.NETWORK ->
            networkTransactions.find(subjectId)?.let { transaction ->
                MaterializedEvidenceSubject(
                    label =
                        "${transaction.capture.request.method} " +
                            "${transaction.capture.request.url.host}${transaction.capture.request.url.path}",
                    snapshotJson = transaction.detailJson(),
                    attachmentId = null,
                )
            }
        EvidenceKind.TIMELINE ->
            // No pluginId constraint: TIMELINE is the generic "flag any event" kind, unlike
            // SCREENSHOT/CRASH below which each claim a specific capture plugin.
            timeline.findEvent(subjectId)?.let { event ->
                MaterializedEvidenceSubject(event.summary, event.evidenceSnapshotJson(), event.attachmentId)
            }
        EvidenceKind.SCREENSHOT ->
            // A client-supplied kind must not be trusted on its own: without this pluginId check, any
            // timeline event could be flagged as a SCREENSHOT even though its evidenceApplicability
            // fallback (see buildEvidenceExportRequest) treats every SCREENSHOT-kind item as
            // unredacted-by-construction. Requiring the event to actually be a "screenshot" plugin
            // event closes that gap at the source.
            timeline
                .findEvent(subjectId)
                ?.takeIf { it.pluginId == SCREENSHOT_PLUGIN_ID }
                ?.let { event ->
                    MaterializedEvidenceSubject(event.summary, event.screenshotSnapshotJson(), event.attachmentId)
                }
        EvidenceKind.CRASH ->
            // Same reasoning as SCREENSHOT above: only a genuine "crash" plugin event may be flagged
            // CRASH.
            timeline
                .findEvent(subjectId)
                ?.takeIf { it.pluginId == CRASH_PLUGIN_ID }
                ?.let { event ->
                    MaterializedEvidenceSubject(event.summary, event.crashSnapshotJson(), null)
                }
        EvidenceKind.SOCKET ->
            socketStore.findMessage(subjectId)?.let { (connection, message) ->
                MaterializedEvidenceSubject(
                    label = "${message.direction.name} ${message.metadata.frameType.name}",
                    snapshotJson = message.evidenceSnapshotJson(connection.url),
                    attachmentId = null,
                )
            }
        EvidenceKind.PUSH ->
            // Mirrors dashboard.js's own convention (socketFlagId's push sibling): PushEvent has no
            // durable id of its own, so the subject is the event's position in pushStore.events() --
            // the same ordering GET /api/v1/push/events already returns.
            subjectId.toIntOrNull()?.let(pushStore.events()::getOrNull)?.let { push ->
                MaterializedEvidenceSubject("${push.provider} · ${push.lifecycle.name}", push.json(), null)
            }
    }

/**
 * Linear scan across timeline pages by cursor, bounded by [MAX_TIMELINE_SCAN_PAGES]
 * (100,000 events at [TimelineQuery.MAX_PAGE_LIMIT] per page). [Timeline] exposes no lookup-by-id
 * beyond [Timeline.contains], which this uses as a cheap short-circuit before ever paging.
 */
@Suppress("ReturnCount") // One early-exit per loop-termination condition reads clearest.
private fun Timeline.findEvent(id: String): StoredEvent? {
    if (id.isBlank() || !contains(id)) return null
    var cursor: String? = null
    repeat(MAX_TIMELINE_SCAN_PAGES) {
        val page = page(TimelineQuery(limit = TimelineQuery.MAX_PAGE_LIMIT, cursor = cursor, sort = TimelineSort.ASC))
        val success = page as? TimelinePage.Success ?: return null
        success.events.firstOrNull { it.id == id }?.let { return it }
        if (!success.hasMore || success.nextCursor == null) return null
        cursor = success.nextCursor
    }
    return null
}

/**
 * Resolves dashboard.js's `socketFlagId` convention (`connectionId@timestampEpochMs`) back to the
 * connection and message it named -- [SocketMessage] carries no id of its own. A shared timestamp
 * across two frames on the same connection resolves to the first match, same as the client-side
 * convention this mirrors.
 */
@Suppress("ReturnCount") // One early-exit per parse/lookup stage reads clearest.
private fun SocketStore.findMessage(subjectId: String): Pair<SocketConnection, SocketMessage>? {
    val separator = subjectId.lastIndexOf('@')
    if (separator <= 0 || separator == subjectId.lastIndex) return null
    val connectionId = subjectId.substring(0, separator)
    val timestampEpochMs = subjectId.substring(separator + 1).toLongOrNull() ?: return null
    val connection = connection(connectionId) ?: return null
    val message = connection.messages.firstOrNull { it.timestampEpochMs == timestampEpochMs } ?: return null
    return connection to message
}

/**
 * A small, self-produced tagsJson (`{"key":"value",...}`) never needs a general JSON parser to read
 * back -- but it does need to parse the *same* grammar the device side does. This mirrors
 * `InspectorJsonText.jsonStringField` (`sdk/full`, not reachable from this module) field for field:
 * `(?:[^"\\]|\\.)*` so an escaped quote inside the value never truncates the match early, and
 * [unescapeJsonString] undoes the same escaping [String.escapeJson] in this file applies, so a
 * `thread`/`kind` tag containing a backslash, quote, newline, or control character round-trips
 * byte-for-byte instead of coming back double-escaped or cut off at the first embedded quote.
 */
private fun String.tagValue(key: String): String? {
    val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(this) ?: return null
    return match.groupValues[1].unescapeJsonString()
}

private const val UNICODE_HEX_DIGITS = 4
private const val UNICODE_ESCAPE_LENGTH = 6
private const val HEX_RADIX = 16

/**
 * Single left-to-right pass, so an escaped backslash (`\\`) is consumed as one unit and a following
 * `n`/`r`/`t`/`"` is never misread as part of an escape -- the bug a chain of `replace` calls has.
 * Kept identical to `InspectorJsonText.unescapeJsonString` (`sdk/full`) since both sides must decode
 * the same escaped tag value back to the same raw string -- see [String.tagValue]'s KDoc.
 */
private fun String.unescapeJsonString(): String {
    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        val current = this[index]
        if (current != '\\' || index + 1 >= length) {
            out.append(current)
            index += 1
            continue
        }
        val next = this[index + 1]
        val simple = simpleJsonEscape(next)
        index =
            when {
                simple != null -> {
                    out.append(simple)
                    index + 2
                }
                next == 'u' -> appendUnicodeJsonEscape(out, index)
                else -> {
                    out.append(current)
                    index + 1
                }
            }
    }
    return out.toString()
}

/** Maps a single-character JSON escape to its literal, or null for `\\uXXXX` / unknown escapes. */
private fun simpleJsonEscape(escaped: Char): Char? =
    when (escaped) {
        '"', '\\', '/' -> escaped
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'b' -> '\b'
        'f' -> '\u000C'
        else -> null
    }

/** Appends the decoded `\\uXXXX` at [backslashIndex] (or the raw backslash if malformed); returns the next index. */
private fun String.appendUnicodeJsonEscape(
    out: StringBuilder,
    backslashIndex: Int,
): Int {
    val hexStart = backslashIndex + 2
    val hexEnd = hexStart + UNICODE_HEX_DIGITS
    val code = if (hexEnd <= length) substring(hexStart, hexEnd).toIntOrNull(HEX_RADIX) else null
    return if (code != null) {
        out.append(code.toChar())
        backslashIndex + UNICODE_ESCAPE_LENGTH
    } else {
        out.append('\\')
        backslashIndex + 1
    }
}

/**
 * TIMELINE snapshot: summary/level/source/plugin/tags verbatim, plus the raw payload (message/stack
 * trace live inside it, when the plugin emits one) and attachmentId.
 */
private fun StoredEvent.evidenceSnapshotJson(): String =
    buildString {
        append("{\"pluginId\":\"").append(pluginId.escapeJson()).append('"')
        append(",\"type\":\"").append(type.escapeJson()).append('"')
        append(",\"severity\":").append(severity)
        append(",\"wallTimeMs\":").append(wallTimeMs)
        append(",\"summary\":\"").append(summary.escapeJson()).append('"')
        append(",\"tags\":").append(tagsJson)
        payloadJson?.let { append(",\"payload\":").append(it) }
        attachmentId?.let { append(",\"attachmentId\":\"").append(it.escapeJson()).append('"') }
        append('}')
    }

/**
 * SCREENSHOT snapshot: width/height (from the "screenshot.captured" event's tags) and attachmentId.
 * Byte count is never persisted anywhere this route can read it back from, so it is omitted rather
 * than fabricated.
 */
private fun StoredEvent.screenshotSnapshotJson(): String {
    val widthPx = tagsJson.tagValue("widthPx")
    val heightPx = tagsJson.tagValue("heightPx")
    return buildString {
        append("{\"widthPx\":").append(widthPx?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append(",\"heightPx\":").append(heightPx?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append(",\"attachmentId\":").append(attachmentId?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append('}')
    }
}

/**
 * CRASH snapshot: kind/thread (from the crash event's tags), summary, and the raw payload
 * (all-thread dump + breadcrumbs). Mirrors CrashCapture's own auto-flag snapshot shape byte-for-byte.
 */
private fun StoredEvent.crashSnapshotJson(): String {
    val kind = tagsJson.tagValue("kind")
    val thread = tagsJson.tagValue("thread")
    return buildString {
        append("{\"kind\":").append(kind?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append(",\"thread\":").append(thread?.let { "\"${it.escapeJson()}\"" } ?: "null")
        append(",\"summary\":\"").append(summary.escapeJson()).append('"')
        payloadJson?.let { append(",\"payload\":").append(it) }
        append('}')
    }
}

/** SOCKET snapshot: connection URL, frame direction/opcode, payload, and timestamp. */
private fun SocketMessage.evidenceSnapshotJson(connectionUrl: String): String =
    "{\"connectionUrl\":\"${connectionUrl.escapeJson()}\",\"connectionId\":\"${connectionId.escapeJson()}\"," +
        "\"direction\":\"${direction.name}\",\"frameType\":\"${metadata.frameType.name}\"," +
        "\"timestampEpochMs\":$timestampEpochMs,\"payload\":${payload.json()}}"

/**
 * [StoredEvidenceItem.snapshotJson] is already well-formed JSON (produced above, or truncated
 * honestly by the store); it is embedded raw, matching how StoredEvent.payloadJson is embedded
 * elsewhere in this file. `redactionApplicability` is a live lookup against the same stored
 * attachment `attachmentId` already names -- this is the evidence tray's badge source, so it must
 * come from the record, never from `kind` (the client-side rule this replaces).
 */
private suspend fun StoredEvidenceItem.json(attachmentMetadataReader: suspend (String) -> StoredAttachment?): String {
    val applicability = attachmentId?.let { attachmentMetadataReader(it)?.redactionApplicability }
    return "{\"id\":\"${id.escapeJson()}\",\"kind\":\"${kind.name}\",\"subjectId\":\"${subjectId.escapeJson()}\"," +
        "\"label\":\"${label.escapeJson()}\",\"flaggedAtMs\":$flaggedAtMs,\"snapshot\":$snapshotJson," +
        "\"attachmentId\":${attachmentId?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"redactionApplicability\":${applicability?.let { "\"${it.name}\"" } ?: "null"}}"
}

private fun StoredEvidenceReport.json(): String =
    "{\"sessionId\":\"${sessionId.escapeJson()}\",\"severity\":\"${severity.name}\"," +
        "\"summary\":${summary?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"expected\":${expected?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"actual\":${actual?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"updatedAtMs\":$updatedAtMs}"

// ============================================================================================
// Evidence export bundle (C -- see the design spec's "Evidence bundle" section). Requested through
// the existing POST /api/v1/exports (scope=EVIDENCE) and GET /api/v1/exports/estimate routes; no new
// transport. report.md/report.json are built from the persisted report row and the snapshotted
// items (never from a live re-query), so they no longer depend on the browser's cache. network.har
// and postman_collection.json are best-effort against the live NetworkTransactionStore, matching
// GET /api/v1/network/har -- the flagged item still names its subject even if the live row is gone
// by export time, it just cannot contribute a HAR/Postman entry for that one item.
// ============================================================================================

@Suppress("LongParameterList") // Every collaborator an evidence bundle draws from is threaded through.
private suspend fun buildEvidenceExportRequest(
    sessionId: String,
    maxBytes: Long,
    destination: File,
    evidenceStore: EvidenceStore?,
    networkTransactions: NetworkTransactionStore,
    metadata: ServerMetadata,
    attachmentReader: suspend (String) -> ByteArray?,
    attachmentMetadataReader: suspend (String) -> StoredAttachment?,
    sessionSnapshotProvider: suspend () -> StoredSession?,
    redaction: RedactionEngine,
): ExportRequest? {
    val store = evidenceStore ?: return null
    val items = store.items(sessionId)
    val report = store.report(sessionId)
    val networkTransactionsForItems =
        items.filter { it.kind == EvidenceKind.NETWORK }.mapNotNull { networkTransactions.find(it.subjectId) }
    val sessionSnapshot = runCatching { sessionSnapshotProvider() }.getOrNull()
    // Metadata only, never the attachment's own bytes, on the common path: a StoredAttachment row
    // (storedLength, redactionApplicability) is orders of magnitude cheaper to read than the blob it
    // describes, so this can size the *whole* bundle -- and EventExportWriter can therefore refuse an
    // oversized one -- without a single screenshot or captured body being pulled into heap.
    // attachmentReader itself is wired in as EvidenceBundleAttachment.open, invoked lazily by
    // EventExportWriter only for a bundle that already cleared the size gate, one attachment at a time.
    val attachments =
        items.mapNotNull { item ->
            val attachmentId = item.attachmentId ?: return@mapNotNull null
            // Trustworthy as of the pluginId check in materializeEvidenceSubject: an item can only be
            // EvidenceKind.SCREENSHOT if the underlying event's pluginId actually was "screenshot", so
            // this can no longer be spoofed by flagging a screenshot under a different kind.
            val screenshot = item.kind == EvidenceKind.SCREENSHOT
            val directory = if (screenshot) "screenshots" else "bodies"
            val extension = if (screenshot) "png" else "bin"
            val path = "attachments/$directory/${attachmentId.sha256Hex().take(ATTACHMENT_NAME_HASH_CHARS)}.$extension"
            val stored =
                try {
                    attachmentMetadataReader(attachmentId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (stored != null) {
                EvidenceBundleAttachment(
                    path = path,
                    sizeBytes = stored.storedLength,
                    redactionApplicability = stored.redactionApplicability,
                    open = { attachmentId.readSafely(attachmentReader) },
                )
            } else {
                // The metadata row is unavailable (a transient read failure, or the record is already
                // gone): there is no cheap size to gate on, so -- unlike the common case above -- this
                // one attachment is read eagerly, right here. That is a bounded cost on a rare failure
                // path, not the systemic "every attachment, every export" cost the size gate exists to
                // prevent. What must not happen is guessing at a redaction outcome nobody can back up:
                // only a screenshot's applicability is knowable without the row at all (pixels are
                // never text-redacted, by construction -- see RedactionApplicability's own KDoc);
                // everything else reports unknown (null) here, never a fabricated APPLIED.
                val bytes = attachmentId.readSafely(attachmentReader) ?: return@mapNotNull null
                EvidenceBundleAttachment(
                    path = path,
                    sizeBytes = bytes.size.toLong(),
                    redactionApplicability = if (screenshot) RedactionApplicability.NOT_APPLICABLE else null,
                    open = { bytes },
                )
            }
        }
    val bundle =
        EvidenceBundleContent(
            reportMarkdown = evidenceReportMarkdown(report, items, metadata, sessionSnapshot),
            reportJson =
                "{\"report\":${report.json()}," +
                    "\"items\":[${items.map { it.json(attachmentMetadataReader) }.joinToString(",")}]}",
            networkHar = NetworkExport.toHarTransactions(networkTransactionsForItems, redaction),
            postmanCollection = NetworkExport.toPostman(networkTransactionsForItems, redaction),
            sessionJson = evidenceSessionJson(metadata, sessionSnapshot),
            itemCount = items.size,
            attachments = attachments,
        )
    return ExportRequest(sessionId = sessionId, events = emptyList(), destination = destination, maxBytes = maxBytes)
        .withScope(ExportScope.Evidence)
        .withEvidenceBundle(bundle)
}

/** A cancellation-transparent, exception-swallowing attachment read -- a missing/throwing reader is just "no bytes". */
private suspend fun String.readSafely(attachmentReader: suspend (String) -> ByteArray?): ByteArray? =
    try {
        attachmentReader(this)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

/** GET /api/v1/runs row: enough for the previous-run-crashed banner and a future Session & Security view. */
private fun StoredSession.json(): String =
    "{\"id\":\"${id.escapeJson()}\",\"status\":\"${status.name}\"," +
        "\"startedAtEpochMs\":$startedAtMs,\"endedAtEpochMs\":${endedAtMs ?: "null"}," +
        "\"applicationId\":${applicationId?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"appVersionName\":${appVersionName?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"appVersionCode\":${appVersionCode ?: "null"}," +
        "\"buildType\":${buildType?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"deviceModel\":${deviceModel?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"deviceApiLevel\":${deviceApiLevel ?: "null"}," +
        "\"deviceOsVersion\":${deviceOsVersion?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"recordCount\":$recordCount,\"estimatedBytes\":$estimatedBytes}"

private fun evidenceSessionJson(
    metadata: ServerMetadata,
    session: StoredSession?,
): String =
    "{\"applicationId\":\"${metadata.appPackageName.escapeJson()}\"," +
        "\"appVersionName\":\"${metadata.appVersionName.escapeJson()}\"," +
        "\"buildType\":\"${metadata.buildVariant.escapeJson()}\"," +
        "\"deviceModel\":${session?.deviceModel?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"deviceApiLevel\":${session?.deviceApiLevel?.toString() ?: "null"}," +
        "\"deviceOsVersion\":${session?.deviceOsVersion?.let { "\"${it.escapeJson()}\"" } ?: "null"}}"

/**
 * Human-readable evidence report: severity/summary/expected/actual, environment, and an
 * attachment/truncation-marked item index -- built entirely from the persisted report row and the
 * snapshotted items.
 */
private fun evidenceReportMarkdown(
    report: StoredEvidenceReport,
    items: List<StoredEvidenceItem>,
    metadata: ServerMetadata,
    session: StoredSession?,
): String =
    buildString {
        append("# QA Evidence Report\n\n")
        append("**Severity:** ").append(report.severity.name).append('\n')
        report.summary?.let { append("**Summary:** ").append(it).append('\n') }
        report.expected?.let { append("**Expected:** ").append(it).append('\n') }
        report.actual?.let { append("**Actual:** ").append(it).append('\n') }
        append("\n## Environment\n")
        append("- App: ")
            .append(metadata.appDisplayName)
            .append(" (")
            .append(metadata.appPackageName)
            .append(")\n")
        append("- Version: ").append(metadata.appVersionName).append('\n')
        append("- Build: ").append(metadata.buildVariant).append('\n')
        session?.deviceModel?.let { append("- Device: ").append(it).append('\n') }
        session?.deviceApiLevel?.let { append("- API level: ").append(it).append('\n') }
        session?.deviceOsVersion?.let { append("- OS version: ").append(it).append('\n') }
        append("\n## Flagged items (").append(items.size).append(")\n")
        items.forEachIndexed { index, item ->
            append("\n### ")
                .append(index + 1)
                .append(". [")
                .append(item.kind.name)
                .append("] ")
            append(item.label).append('\n')
            append("- Flagged at: ").append(item.flaggedAtMs).append('\n')
            append("- Attachment: ").append(item.attachmentId ?: "none").append('\n')
            if (item.snapshotJson.contains("\"truncated\":true")) {
                append("- **Snapshot truncated at capture time** -- the full content did not fit the per-item cap.\n")
            }
            append("\n```json\n").append(item.snapshotJson).append("\n```\n")
        }
    }

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and EVIDENCE_BYTE_MASK) }

private const val ATTACHMENT_NAME_HASH_CHARS = 32
private const val EVIDENCE_BYTE_MASK = 0xff
