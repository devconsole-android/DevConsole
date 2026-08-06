# Release checklist

- [ ] Version, changelog, and public API change assessment are complete.
- [ ] Unit, server-contract, sample debug/release, and protected-artifact checks pass.
- [ ] Security suites cover redaction, CSRF/origin, session-code exchange throttling, and authorization.
- [ ] Dependency lock/SBOM and provenance are attached to the signed release artifacts.
- [ ] No critical or high unresolved vulnerability remains.
- [ ] Documentation and samples are validated from a clean checkout.
- [ ] Maven Central publish (see [MAVEN_PUBLISHING.md](MAVEN_PUBLISHING.md)), consumer smoke test,
      signed tag, and release notes are complete.
