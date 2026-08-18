---
name: Bug report
about: Something in the SDK, a sample app, or the docs doesn't work as documented
title: ""
labels: bug
assignees: ""
---

**Do not use this template for a suspected credential exposure, production inclusion,
authentication bypass, or remote code execution.** Report those privately per
[SECURITY.md](../../SECURITY.md) instead.

## What happened

A clear description of the bug.

## Expected behavior

What you expected to happen instead.

## Steps to reproduce

1. …
2. …
3. …

Ideally as a diff against one of the sample apps (`samples/compose-app`, `samples/foundation-app`,
`samples/views-java-app`) or a minimal repro project — that's the fastest path to a fix.

## Environment

- DevConsole module(s) and version(s) affected (e.g. `devconsole:0.1.0-SNAPSHOT`,
  `devconsole-network-okhttp:0.1.0-SNAPSHOT`):
- Integration path: JitPack coordinates, or built from source
  (`./gradlew publishToMavenLocal`)?
- Android Gradle Plugin / Kotlin versions in the consuming project:
- Device/emulator API level and manufacturer:
- Build variant (debug / release / other) and binding mode (loopback / LAN) if relevant:

## Logs / stack trace

```
paste here
```

Redact anything sensitive before pasting — see [docs/SECURITY_AND_REDACTION.md](../../docs/SECURITY_AND_REDACTION.md)
for what the SDK itself redacts (field-name based; not everything is caught).
