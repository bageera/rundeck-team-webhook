rundeck-team-incoming-webhook-plugin
====================================

Sends Rundeck job notification messages to a Microsoft Teams channel via a
Teams Incoming Webhook. Based on
[rundeck-slack-plugin](https://github.com/bitplaces/rundeck-slack-plugin)
and run-hipchat-plugin.

Version 0.9.0-yeast-bloom adds support for **Workflows (Power Automate)
webhooks** with Adaptive Cards — required because Microsoft retired the
Office 365 Connectors format this plugin originally used (retirement
completed 2026-05-22, see [issue #6](https://github.com/bageera/rundeck-team-webhook/issues/6)).
It also modernizes the build (Gradle 9 / Java 11 target), upgrades FreeMarker
to 2.3.34, and fixes several bugs (proxy NullPointerException, UTF-8 payload
encoding, JSON escaping in message templates). See [CHANGELOG.md](CHANGELOG.md).

## Download jarfile

1. Download the jar from [releases](https://github.com/bageera/rundeck-team-webhook/releases).
2. Copy it to `$RDECK_BASE/libext`.
3. Restart Rundeck (or reload plugins) so the new version is picked up.

## Build

Requires a JDK (11+ works; the plugin targets Java 11 bytecode):

    ./gradlew clean build

The plugin jar lands in `build/libs/` and embedded libs in
`build/output/lib/`. The `rundeck-core` dependency is `compileOnly` — it is
provided by Rundeck at runtime and is not bundled into the plugin archive.
Unit tests run automatically as part of `build`; run them alone with
`./gradlew test`.

Install locally from source:

    cp build/libs/*.jar $RDECK_BASE/libext

## Configuration

### Workflows (Power Automate) webhooks — recommended

Microsoft retired connector webhooks; create a Workflows webhook instead:

1. In Teams: **Workflows** app → "Send messages in Teams using incoming
   webhooks" template (or create a flow with the
   **"When a Teams webhook request is received"** trigger).
2. Copy the generated webhook URL (a `webhook.office.com` address).
3. Paste it as the `WebHook URL` below.

The plugin auto-detects Workflows URLs and posts an **Adaptive Card**
(job name, color-coded status, execution link, "View in Rundeck" button).
Workflows webhooks accept with HTTP 202 and an empty body.

Note: messages post as the Flow bot; bot icon/name customization is not
available for webhook posts (Microsoft limitation, not the plugin's).

### Legacy Office 365 Connector webhooks

If your tenant still had a connector webhook, its message cards
(MessageCard format) are still supported — the plugin keeps the legacy
payload for any non-`webhook.office.com` URL, or force it with
`Message Format: card`. Screenshots:

On success:

![on success](on_success.png)

On failure:

![on failure](on_failure.png)

### Settings

- `WebHook URL` — Teams Incoming Webhook URL (required).
- `Message Format` — `auto` (default: Adaptive Card for
  `webhook.office.com` URLs, MessageCard otherwise), or pinned
  `adaptive` / `card`.

The webhook URL is a bearer credential for your channel — store it only in
Rundeck configuration, never in source control. See
[SECURITY.md](SECURITY.md) for the security policy (private reports:
security@nocturnalinc.com) and operator security notes.

## Proxy support

If Rundeck runs behind an HTTP proxy, the plugin honors the standard Java
system properties (set them in `$RDECK_BASE/etc/profile` / `rundeckd` java
options):

    -Dhttp.proxyHost=proxy.example.com -Dhttp.proxyPort=3128

Optional proxy authentication:

    -Dhttp.proxyUser=domain\\user -Dhttp.proxyPassword=secret

## Contributors

* Original [higanworks/rundeck-slack-incoming-webhook-plugin](https://github.com/higanworks/rundeck-slack-incoming-webhook-plugin) author: @sawanoboly
* Original [hbakkum/rundeck-hipchat-plugin](https://github.com/hbakkum/rundeck-hipchat-plugin) author: Hayden Bakkum @hbakkum
* Original [bitplaces/rundeck-slack-plugin](https://github.com/bitplaces/rundeck-slack-plugin) authors
  * @totallyunknown
  * @notandy
  * @lusis
* @sawanoboly