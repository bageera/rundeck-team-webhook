# Security Policy

## Supported versions

We patch security issues only for the most recent release line.

| Version             | Supported |
| ------------------- | --------- |
| 0.8.0-yeast-bloom   | Yes       |
| <= 0.7-proxy        | No        |

## Reporting a vulnerability

**Please do not open a public GitHub issue, pull request, or discussion for
security concerns.**

Report vulnerabilities privately to:

**security@nocturnalinc.com**

When reporting, please include as much of the following as you can:

- A description of the issue and its security impact
- Step-by-step instructions or a proof of concept to reproduce it
- Affected version(s) (and the plugin jar filename if relevant)
- Any suggested mitigation or fix, if you have one
- Your contact info (optional, but useful for follow-up)

You can encrypt sensitive reports with our public key
(https://keys.openpgp.org — search for the address above).

### What happens next

1. You will receive an acknowledgement within 5 business days.
2. We will investigate and may ask follow-up questions.
3. We aim to publish a fix along with the next release and will credit you in
   the release notes unless you prefer to remain anonymous.

## Disclosure policy

We follow coordinated disclosure: please give us a reasonable window
(typically 90 days) to ship a fix before publishing technical details.

## Security notes for operators

This plugin is a notification bridge between a Rundeck server and a Microsoft
Teams Incoming Webhook. Things operators should know:

- The **WebHook URL** is a bearer secret: anyone who holds it can post
  arbitrary messages to your Teams channel. Rundeck stores it in the project
  or framework configuration, so protect Rundeck config storage (e.g.
  keystorage / encryption) accordingly and never commit it to source control.
- All values interpolated into the message payload (job names, groups,
  usernames, URLs) are JSON-escaped via FreeMarker `?json_string`, but the
  values themselves originate from Rundeck jobs — treat Rundeck as a trusted
  component in your threat model and lock down who can create/modify job
  names and notification configurations.
- Outbound requests honor the `http.proxyHost` / `http.proxyPort` /
  `http.proxyUser` / `http.proxyPassword` Java system properties and are sent
  with 10 second connect/read timeouts.
- The plugin only supports the O365 Connector Incoming Webhook format; there
  is no inbound surface — it only makes outbound POSTs to the configured URL.
  Verify the configured URL is HTTPS ( Teams-generated URLs are) before
  saving it.