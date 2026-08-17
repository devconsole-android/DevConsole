# Default LAN and Mock Behavior

## Summary

Make LAN the primary server-binding default across the library while retaining loopback as an explicit secondary choice. Preserve the full/debug runtime's enabled mock engine by default and keep the noop/release artifact's mock engine disabled for production safety.

## Behavior and interfaces

- `BrowserConfig()` defaults to `BrowserBinding.LAN`, affecting the in-app inspector More-screen Start action.
- `StartRequest()` defaults to `BindingMode.LAN`, affecting programmatic `DevConsole.startBrowser()` calls.
- The lower-level `sdk:server-api` `StartRequest()` default is aligned to LAN so its published abstraction has no conflicting default.
- Hosts can explicitly select `BrowserBinding.LOOPBACK` or `BindingMode.LOOPBACK`; all existing LAN permission and eligible-interface checks remain unchanged.
- The full/debug `MockEngine` remains enabled by default. The noop/release `MockEngine` remains disabled, and explicit capture-category gating still takes precedence.

## Implementation and documentation

- Change the existing constructor defaults and stale KDoc/comments in `sdk:api` and `sdk:server-api`.
- Update README, setup, and threat-model guidance to describe LAN as the default and loopback as the explicit safer override.
- Do not add a new configuration field or alter permission handling, capture-category semantics, mock kill-switch behavior, or UI loading-state defaults.

## Tests and acceptance

- API tests assert LAN defaults for `BrowserConfig()` and public `StartRequest()`.
- Server-API tests assert the aligned lower-level `StartRequest()` default.
- Full-runtime tests assert default More-screen LAN binding, explicit loopback binding, and enabled full-runtime mocks.
- Noop tests assert mocks remain disabled.
- Run affected API, server, mocks, and full unit tests plus compilation/API checks; confirm no documentation or comments still claim loopback is the default.

## Assumptions

- “Default mock enabled” means the real/debug library runtime only; the noop artifact is intentionally excluded.
- “Default LAN enabled” applies to both existing start entry points, not just the in-app More screen.
- Loopback remains available and is never selected automatically as a fallback when LAN was requested.
