# Migration guide

## From the original DevConsole integration

1. Apply the `io.github.devconsole-android` Gradle plugin to the Android application module.
2. Depend on `:sdk:full` only in explicitly allowlisted debug/internal variants and on `:sdk:noop` in every protected variant.
3. Call `DevConsole.initialize(application)` once during application startup.
4. Start the server with `DevConsole.startBrowser()` (or Java's `startBrowserAsync()`), inspect the typed result, and stop it with `DevConsole.stop()` when the debug session ends.

Validate each debug and release variant with `verifyDevConsoleProtectedArtifacts` before rollout.

## Pre-1.0 breaking changes

The SDK is pre-1.0 (see [CHANGELOG.md](../CHANGELOG.md)), so the API surface above has moved more
than once during active development — most recently a facade reshape (`start()` →
`startBrowser()`/`startBrowserAsync()`, returning `StartResult.Started(endpoint, access)`), the
removal of `BindingMode.AUTO`/NSD/auto-start, the replacement of pairing/role-based access with the
single SESSION_CODE flow, and the removal of the generic plugin framework. If you're carrying code
against an earlier snapshot of this repository, check the **Unreleased** section of
[CHANGELOG.md](../CHANGELOG.md) for the full list before updating.
