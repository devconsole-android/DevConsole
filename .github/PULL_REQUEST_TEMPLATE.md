## What

One or two sentences on what changed and why (the "why" matters more than the "what" — the diff
already shows the what).

## Verification

Which `./gradlew` commands did you run, and what did they confirm? (See
[CONTRIBUTING.md](../CONTRIBUTING.md#building-and-testing) for the standard set.)

- [ ] `./gradlew testDebugUnitTest` — passing
- [ ] `./gradlew ktlintCheck detekt` — clean (or new detekt findings are justified, not baselined away)
- [ ] `./gradlew apiCheck` — clean, or `apiDump` is included in this diff because the public API changed
- [ ] If this touches a debug/release or protected-variant boundary: sample assemble/verify commands
      run for the affected sample(s) (`:samples:<name>:assembleRelease
      :samples:<name>:verifyDevConsoleProtectedArtifacts`)

## Checklist

- [ ] Capture-path code (network/socket/push) never throws into the host app
- [ ] No new sensitive data is captured, logged, or exported without going through
      `RedactionEngine` first
- [ ] Docs updated if behavior, an API, or a config field changed
      (see [docs/README.md](../docs/README.md) for the index)
- [ ] This PR is scoped to one logical change

## Anything reviewers should look at closely

Optional — call out anything you're unsure about, a tradeoff you made, or a spot you want a second
look at.
