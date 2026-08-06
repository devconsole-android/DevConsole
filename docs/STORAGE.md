# Event storage and retention

Timeline events — network calls, WebSocket traffic, logs, crashes, push — are written
through to a Room database on the device, so they survive process death. That matters most in the
case you actually care about: a crash erases an in-memory timeline precisely when you need the
minutes leading up to it.

## How it works

Writes are **write-through, not read-through**. Appends go to an in-memory timeline *and* to a
bounded, batching queue that drains to Room on a background thread. Reads are served from memory,
because `Timeline.page` is synchronous and already implements cursors and filtering while
`EventStore` is suspending and session-scoped.

The practical consequences:

- **Nothing blocks your app.** If storage stalls, the queue drops its oldest entries and the app
  carries on. Losing history is acceptable; adding latency to a host request is not.
- **If the database cannot be opened at all**, the console runs in memory only rather than failing
  to start.
- **Reads within a session do not hit disk**, so dashboard paging stays fast.

## Retention

`EventQuotaPruner` caps the store at 50,000 events, dropping oldest-first. Attachments are stored on
disk under the app's no-backup directory with their own quota, and are written via an atomic rename
so a partial file is never visible.

Everything is redacted **before** it reaches storage. The database holds no unredacted payloads, so
the retention window is not a growing pile of secrets.

## Where it lives

`sdk:storage-api` defines the contract with no Android or Room types in it, so nothing downstream
needs to know which persistence mechanism is active. `sdk:storage-room` is the Room implementation.
Tests use in-memory implementations of the same contract.

The database file is `devconsole-events.db` in the app's standard database directory, and it exists
only in debug builds — `sdk:noop` has no storage at all.

## Clearing it

`POST /api/v1/session/integrity/reset` clears session overrides, not history. To drop stored events,
uninstall the debug build or clear its data; there is deliberately no remote "delete all events"
endpoint, since an authenticated browser being able to erase the audit trail is the wrong default.
