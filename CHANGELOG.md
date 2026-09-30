# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.0-yeast-bloom] - 2026-09-30

First release rebuilt for modern Rundeck/Gradle. Adds the project's first unit
test suite and CI-ready build. Named releases start here.

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

### Added
- `CHANGELOG.md` (this file).
- First unit test suite (JUnit 5): template rendering/escaping for all three
  triggers with hostile job names, trigger validation, and webhook POST
  success/failure paths exercised against an in-process HTTP server,
  including UTF-8 round-trip verification.

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