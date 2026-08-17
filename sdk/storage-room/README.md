# DevConsole — Room Database Integration

`sdk:storage-room` providesRoom database event recording and timeline integration for DevConsole.

## Quick Setup

1. Add dependency to your build file:
```kotlin
debugImplementation("com.github.devconsole-android.DevConsole:devconsole-storage-room:<version>")
```

`sdk:full` wires this module in automatically for durable timeline storage — no additional
registration is required. Scope it to `debugImplementation` (never plain `implementation`) yourself
if you depend on it directly: it is not in the Gradle plugin's protected-artifact denylist, so
nothing else catches a release build that accidentally ships it.
