# Security and redaction

See [SECURITY.md](../SECURITY.md) at the repository root for the vulnerability-reporting policy.
This document covers how redaction actually works, for anyone integrating or auditing it.

## Where redaction happens

Before an event reaches any store or the live dashboard stream — never after. Every capture path
(network, WebSocket, push) shares one `RedactionEngine`, so the same field is redacted identically
regardless of which inspector captured it.

## Default policy

`RedactionPolicy.default()`'s 25 sensitive field names (case-insensitive, matched against header
names, JSON keys, and form field names):

```text
authorization      proxy-authorization  www-authenticate    authentication
cookie             set-cookie           x-api-key           api-key
apikey             x-auth-token         x-access-token      x-csrf-token
x-xsrf-token       access_token         refresh_token       id_token
token              jwt                  password             passcode
passphrase         secret               client_secret       private_key
session_id
```

A matched value is replaced wholesale with `<redacted>`. Independently of the field-name list, any
`Bearer <token>` text pattern is redacted wherever it appears (e.g. inside a body that isn't valid
JSON/form data), so a leaked bearer token in a non-standard location is still caught.

## Redaction strategies beyond removal

The engine's default behavior is whole-value replacement. The wire-protocol design additionally
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

Redaction, as described above, is a text operation: it recognizes field names and pattern-matches
`Bearer <token>` text, then replaces the matched value. Some content this SDK stores — most notably a
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
