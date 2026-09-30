# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.9.1] - 2026-09-30

Rundeck-contract alignment release, built directly from the current Rundeck
developer documentation (docs.rundeck.com/docs/developer/). Continues the
yeast-bloom line as a patch bump.

### Fixed
- **Trigger normalization** (docs: Notification Triggers): Rundeck versions
  have sent both unprefixed (`start`) and on-prefixed (`onstart`) trigger
  names, and the documented trigger set is five events, not three.
  Triggers are now normalized by stripping an optional `on` prefix and
  lowercasing; `avgduration` and `retryablefailure` render dedicated copy
  ("exceeded average duration", "failed — will be retried") instead of the
  former `IllegalArgumentException` that killed those notifications.
- **Unknown/forward-compat triggers** render a neutral fallback card
  (accent color) rather than failing the notification — notification
  plugins are the last stop of the job lifecycle, per docs.
- Templates harden all lookups with `!''` defaults, eliminating
  missing-value FreeMarker errors on nonstandard execution data.

### Added
- **Documented-but-unused execution fields now on the cards** (docs:
  Execution Data Reference), guarded so start-time events stay clean:
  - `execution.project` / nested `execution.project` (two-path lookup for
    legacy + modern Rundeck data layouts)
  - `failedNodeListString` (completion events only)
  - `job.description`
  - `dateStartedW3c` (adaptive card only)
- **Plugin icon** (`resources/Notification.TeamsNotification.icon.png`,
  manifest bumped to `Rundeck-Plugin-Version: 1.2`) — Teams-purple "T"
  badge in the Rundeck plugin list instead of the generic default.

## [0.9.0-yeast-bloom] - 2026-09-30

Migrates to Microsoft Teams Workflows (Power Automate) webhooks ahead of the
Office 365 Connectors retirement completed on 2026-05-22. Resolves issue #6.

### Fixed
- **Issue #6 — Deprecated connector**: Workflows (Power Automate) webhook
  URLs are now supported end to end.
  - New Adaptive Card payload (`team-adaptive-message.ftl`): job name,
    color-coded status, execution markdown link, FactSet details, and a
    "View in Rundeck" `Action.OpenUrl` button.
  - Format auto-detection: `webhook.office.com` URLs render Adaptive
    Cards; explicit `Message Format` plugin property can pin `adaptive`
    or the legacy `card` (MessageCard) format.
  - Success detection reworked to HTTP-status-based: legacy connector
    webhooks answer `200` + body `1`, Workflows webhooks answer `202`
    Accepted with an empty body — both count as delivered. The old
    body-`"1"` equality check made every Workflows notification look
    like a failure.
  - HTTP 429 (rate limit) produces an actionable error naming the
    Teams ~4 req/s/webhook limit; other failures echo at most 200
    characters of the response body (no full payload dumps).
- Plugin error responses no longer include the raw outbound payload
  (leak fix retained from 0.8.0).

### Added
- `Message Format` plugin property (`auto` | `adaptive` | `card`) with
  URL-host auto-detection.
- `SECURITY.md` security policy — private vulnerability reports to
  security@nocturnalinc.com, coordinated disclosure, operator security
  notes (from 0.8.0).
- `CHANGELOG.md` (this file, from 0.8.0).
- Unit test suite (JUnit 5): template rendering/escaping for all triggers
  with hostile job names, trigger validation, webhook POST success/failure
  paths exercised against an in-process HTTP server, UTF-8 round-trip
  verification, Adaptive Card JSON shape validation, Workflows
  `202`-empty acceptance, rate-limit handling, and format auto-detection
  (from 0.8.0, extended for 0.9.0).

## [0.8.0-yeast-bloom] - 2026-09-30

Modernization release: rebuild for modern Rundeck/Gradle, security hardening.

### Security
- Escape all interpolated values in the Teams JSON payload with FreeMarker
  `?json_string` — job names/groups/usernames can no longer inject JSON or
  markdown links into channel messages.
- Stop returning the raw outbound payload in error messages after a failed
  webhook call (payload leaked usernames and job URLs into logs).

### Fixed
- NullPointerException in `openConnection()` on hosts without proxy system
  properties — the 2018 proxy feature crashed every notification on default
  installs; proxy settings are now optional.
- Silent charset mangling: payload is now written as UTF-8 bytes with
  `Content-Type: application/json; charset=utf-8` (was `writeBytes`, which
  truncates non-ASCII, with no content type at all).
- HTTP connect/read timeouts added (10s each) — a hung Teams endpoint no
  longer hangs the Rundeck notification thread forever.
- Proxy auth header built from UTF-8 bytes instead of platform default
  charset, and only when configured.
- Plugin handle renamed `TeamkNotification` -> `TeamsNotification` (typo
  fix; visible on the Rundeck plugin list).

### Changed
- Build modernized: Gradle 9.5.1 wrapper (https distribution), Java 11
  bytecode target, `plugins { id 'java' }` on modern Gradle syntax.
- FreeMarker upgraded 2.3.20 -> 2.3.34; template loading is a single static
  configuration instead of being rebuilt per notification.
- `rundeck-core` is now `compileOnly` — provided by Rundeck at runtime, no
  longer bundled into the plugin archive.
- Version 0.7-proxy -> 0.8.0-yeast-bloom.
- README rewritten for current build/install steps; `.gitignore` covers
  Gradle build state and macOS droppings.

## [0.7-proxy] - 2018-06-04

Historical entry reconstructed from git log.

### Added
- HTTP proxy support via Java system properties
  (`-Dhttp.proxyHost`, `-Dhttp.proxyPort`, `-Dhttp.proxyUser`,
  `-Dhttp.proxyPassword`) in commit 0b55198.

## [0.7] - 2014-04-24

Historical entry reconstructed from git log.

### Added
- Initial Microsoft Teams support, forked from the Slack
  incoming-webhook plugin lineage (HipChat plugin ancestry).
- Teams Incoming Webhook `WebHook URL` plugin property.
- FreeMarker template rendering of job start/success/failure
  notifications with Teams Connector cards.