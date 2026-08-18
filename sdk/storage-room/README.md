# DevConsole — Room Database Integration

`sdk:storage-room` providesRoom database event recording and timeline integration for DevConsole.

## Quick Setup

1. Add dependency to your build file:
```kotlin
debugImplementation("com.github.devconsole-android.DevConsole:devconsole-storage-room:v<version>")
```

`v<version>` is a JitPack version, which is the git tag verbatim — releases are tagged `v*`, so
`v1.2.2`, not `1.2.2`. The plugin is the exception: it comes from the Gradle Plugin Portal, where
its version is bare.

`sdk:full` wires this module in automatically for durable timeline storage — no additional
registration is required. Scope it to `debugImplementation` (never plain `implementation`) yourself
if you depend on it directly: it is not in the Gradle plugin's protected-artifact denylist, so
nothing else catches a release build that accidentally ships it.
