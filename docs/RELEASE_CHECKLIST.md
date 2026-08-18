# Release checklist

- [ ] Version, changelog, and public API change assessment are complete.
- [ ] Unit, server-contract, sample debug/release, and protected-artifact checks pass.
- [ ] Security suites cover redaction, CSRF/origin, session-code exchange throttling, and authorization.
- [ ] Dependency lock/SBOM and provenance are attached to the release artifacts.
- [ ] No critical or high unresolved vulnerability remains.
- [ ] Documentation and samples are validated from a clean checkout.
- [ ] `./gradlew publishToMavenLocal` passes from a clean checkout (this is what JitPack runs).
- [ ] Signed tag pushed, JitPack build green for that tag (see [PUBLISHING.md](PUBLISHING.md)),
      consumer smoke test and release notes are complete.
