# DevConsole — Room Database Integration

`sdk:storage-room` providesRoom database event recording and timeline integration for DevConsole.

## Quick Setup

1. Add dependency to your build file:
```kotlin
debugImplementation("com.github.devconsole-android.DevConsole:devconsole-storage-room:<version>")
```

`<version>` is a JitPack version — `1.2.4` for the current release. Releases are tagged `v1.2.4`,
and JitPack resolves a bare version against the `v`-prefixed tag, so the `v` is optional; the bare
form is used here because it also matches the plugin's version on the Gradle Plugin Portal.

`sdk:full` wires this module in automatically for durable timeline storage — no additional
registration is required. Scope it to `debugImplementation` (never plain `implementation`) yourself
if you depend on it directly: it is not in the Gradle plugin's protected-artifact denylist, so
nothing else catches a release build that accidentally ships it.
