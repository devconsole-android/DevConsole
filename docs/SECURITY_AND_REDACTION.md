# Security and redaction

See [SECURITY.md](../SECURITY.md) at the repository root for the vulnerability-reporting policy.
This document covers how redaction actually works, for anyone integrating or auditing it.

## Opt-in security layers

DevConsole is a debug-only developer tool. Runtime security layers are off by default, and
development features are enabled. Configure the protections your environment needs explicitly.

| Layer | Default | One-line opt-in |
|---|---|---|
| Redaction | Off; no field names or text patterns | `DevConsoleConfig(redactionPolicy = RedactionPolicy.recommended())` |
| Rate limiting | Off | `.withNetworkProtection(NetworkProtection(rateLimiting = true))` |
| Host and Origin checks | Off | `.withNetworkProtection(NetworkProtection(hostAndOriginChecks = true))` |
| Both network protections | Off | `.withNetworkProtection(NetworkProtection.recommended())` |
| Browser authentication | `BrowserSecurity.NONE` | `.withBrowserSecurity(BrowserSecurity.SESSION_CODE)` |
| Read-only editing capabilities | Off; preferences, feature flags, database, files, mocks, request execution, and capture rules are all editable | `.withEditingCapabilities(EditingCapabilities.readOnly())` |
| Composer restriction | Enabled, any host; empty allowlist permits all | `DevConsoleConfig(composerAllowedHosts = setOf("api.example.test"))` |
| Disable Composer | Composer enabled | `DevConsoleConfig(composerEnabled = false)` |
| Disable state mutations | State mutations enabled | `DevConsoleConfig(stateMutationsEnabled = false)` |
| Disable screenshots | Screenshots enabled | `.withScreenshotPolicy(ScreenshotPolicy(enabled = false))` |

Network settings replace the previous `NetworkProtection` value; use `recommended()` to enable
both switches together. Java uses `DevConsoleConfig.builder().networkProtection(...)` and the other
policy setters. Additive `with...` methods preserve the other configured policies.

Static response headers (`nosniff`, X-Frame-Options, CSP, and no-store) remain enabled. The release
build guard and noop artifact are unchanged: they are build-variant safety nets, not runtime layers.
SESSION_CODE always keeps bearer-token validation, CSRF tokens, session expiry, and the session-code
attempt limiter, even when network protection is off. Origin validation is controlled separately by
`hostAndOriginChecks`.

`GET /api/v1/meta` reports `redaction: { enabled, sensitiveFieldNames }` (`enabled` is true when any
rule exists) and `networkProtection: { rateLimiting, hostAndOriginChecks }`; existing endpoint,
composer, and capture-category fields remain.

## Hardening checklist

Use this checklist on shared networks or with production-like data:

- Set `redactionPolicy = RedactionPolicy.recommended()` and extend its names or patterns for your data.
- Add `.withNetworkProtection(NetworkProtection.recommended())` for budgets and Host/Origin checks.
- Add `.withBrowserSecurity(BrowserSecurity.SESSION_CODE)` for authenticated browser sessions.
- Add `.withEditingCapabilities(EditingCapabilities.readOnly())`; enable individual writes only as needed.
- Set `composerEnabled = false` and `stateMutationsEnabled = false`, or explicitly restrict Composer hosts.
- Add `.withScreenshotPolicy(ScreenshotPolicy(enabled = false))` when screen contents must stay private.
- Bind loopback with `BrowserConfig(binding = BrowserBinding.LOOPBACK)` when LAN access is unnecessary.
- Keep the noop artifact and release-build guard in protected variants.

## Where redaction happens

When enabled, before an event reaches any store or the live dashboard stream. Every capture path
(network, WebSocket, push) shares one `RedactionEngine`, so the same field is redacted identically
regardless of which inspector captured it.

## Default and recommended policies

`RedactionPolicy.default()` returns `RedactionPolicy.none()`: no names, no text patterns, nothing
masked. `RedactionPolicy.recommended()` opts into the previous built-in sensitive field names
(case-insensitive, matched against header
names, JSON keys, and form field names):

```text
cookie             set-cookie           x-api-key           api-key
apikey             x-auth-token         x-access-token      x-csrf-token
x-xsrf-token       access_token         refresh_token       id_token
token              jwt                  password             passcode
passphrase         secret               client_secret       private_key
session_id
```

A matched value is replaced wholesale with `<redacted>`. The historical recommended set preserves
`Authorization` headers and Bearer text. Add `authorization` to `sensitiveFieldNames` or supply
custom `textPatterns` to mask those too. Push tokens, preference values, Remote Config keys, capture
bodies, crash breadcrumbs, and cURL/HAR/Postman exports follow the configured policy. Database
column heuristics and Composer secret-variable masking apply only when redaction is enabled.

## Redaction strategies beyond removal

When enabled, the engine uses whole-value replacement. The wire-protocol design additionally
specifies `KEEP_PREFIX_SUFFIX(4, 4)` and `HASH_SHA256` strategies for cases where a partially
visible or comparable-but-opaque value is more useful than a blank `<redacted>` — these are declared
in the spec but not yet exposed as configuration on `RedactionPolicy` in this codebase; only
`FIXED("<redacted>")`-equivalent behavior ships today.

## Customizing the policy

`RedactionPolicy` is a plain data class — construct your own with an extended
`sensitiveFieldNames` set or additional `textPatterns` if your application has domain-specific
secret shapes (e.g. an internal token format), and pass it as `DevConsoleConfig(redactionPolicy =
...)` (or `DevConsoleConfig.Builder.redactionPolicy(...)` from Java). `PlatformFacadeProvider`
applies it to the shared capture engine on every `initialize()` call
(`redaction.updatePolicy(config.redactionPolicy)`), so every recorder — network, WebSocket, push —
redacts by whatever policy the host supplied, not just the default. `RedactionPolicy.default()` is
only what you get if you don't override it.

## Bounds, not just redaction

`RedactionEngine.redactText` also truncates to a max length (64 KiB default) — bounding is treated
as part of the same safety boundary as redaction, not a separate concern, since an unbounded value
is its own resource-exhaustion risk regardless of whether it's sensitive.

## `RedactionApplicability` and the storage boundary

Redaction, as described above, is a text operation: configured rules recognize field names or match
text patterns, then replace matched values. Some content this SDK stores — most notably a
captured screenshot (see [THREAT_MODEL.md](THREAT_MODEL.md#screenshots-are-unredactable-by-construction))
— is binary, and there is no equivalent operation for a bitmap. `RedactionApplicability` exists so the
storage layer can tell those two situations apart honestly instead of collapsing them into the same
`isRedacted` boolean:

```kotlin
enum class RedactionApplicability {
    /** Text content that went through the RedactionEngine. AttachmentWriteRequest.isRedacted must be true. */
    APPLIED,
    /** Binary content redaction cannot be applied to, e.g. screenshots. Unredacted by construction. */
    NOT_APPLICABLE,
}
```

**The storage-boundary contract.** `AttachmentStore.write` enforces two rejections, both collapsing to
`AttachmentWriteResult.RejectedUnredactedContent`: `redactionApplicability == APPLIED && !isRedacted` —
text content that was supposed to go through redaction but didn't is refused, full stop, rather than
silently persisted — and, as a second, independent check, `redactionApplicability == NOT_APPLICABLE`
claimed for a MIME type redaction *can* reach (`text/*`, `application/json`, `application/xml`, and
their `+json`/`+xml` suffixed variants). `NOT_APPLICABLE` exists only so content redaction genuinely
cannot reach — binary bytes, most notably a screenshot's PNG — can be stored unredacted while
**honestly labelled as such**; it is not a caller-controlled bypass a request can use to skip redaction
on content that could have gone through it. A `NOT_APPLICABLE` claim on e.g. `image/png` never trips
this second check, so a screenshot is stored exactly as before — this closes a bypass on redactable
content, it does not narrow what a genuinely-binary attachment can do.

`AttachmentWriteRequest.redactionApplicability` defaults to `APPLIED` as a trailing parameter (`sdk:
storage-api` carries no binary API validation, so this default kept every pre-existing call site
compiling unchanged when the field was added), and `StoredAttachment` carries the field forward through
every read path.

**Network body attachments are always `APPLIED`, and deliberately do not report it as a live field
anywhere it's already implied.** The only writer of a network-body attachment is the network capture
path, and it always writes with the applied default — there is exactly one possible answer for that
attachment kind, so surfacing a `redactionApplicability` field on network transaction detail JSON would
be a lookup with one static outcome, not information. This is a deliberate omission, not a gap: it was
considered and left alone, with a comment in the source explaining why, when the same field was added
everywhere else an attachment's applicability can actually vary (evidence tray items, the `GET
/api/v1/attachments/{id}` download, the export manifest).

**The `X-DevConsole-Redaction-Applicability` response header.** `GET /api/v1/attachments/{id}` returns
raw bytes as its body, so there's no JSON envelope to carry the field in — it rides as a response
header instead, the one place on that route a client can read the attachment's stored
`RedactionApplicability` without a second request. The header is **omitted**, not defaulted to
`APPLIED`, when the metadata reader is unwired or the attachment row is already gone, so a client can
tell "confirmed unredacted" apart from "confirmed redacted" apart from "unknown" — three states, never
collapsed to two.

## The UNREDACTED badge

The dashboard badges an attachment **UNREDACTED** wherever it appears — the evidence tray, timeline/
crash detail, the export manifest — whenever its `redactionApplicability` is `NOT_APPLICABLE`. That
field is read from **stored data**, not inferred client-side. Earlier in this feature's development the
badge was inferred from "is this a screenshot," which was correct at the time and would have silently
stopped being correct the moment anything else was ever stored as `NOT_APPLICABLE` — exactly the kind
of guess this field exists to make unnecessary. The download response header described above is treated
as the most authoritative source available and reconciles an already-painted badge once the real bytes
arrive, so a stale JSON field can never outlive the fetch that disproves it. An attachment whose
applicability genuinely can't be determined badges **neither way** rather than guessing a default in
either direction.

## Production builds

`devconsole-noop`'s recorders are constructed with `enabled = false`, so in a protected variant the
redaction engine is never invoked at all — not "invoked and redacts everything," genuinely never
called. See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md).

## Exports

Diagnostic exports run redaction a second time at export time, using the *current* policy — an
export can never bypass or predate the original capture-time redaction.
