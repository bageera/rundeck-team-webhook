/*
 * Copyright 2014 Andrew Karpow
 * based on Slack Plugin from Hayden Bakkum
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.bitplaces.rundeck.plugins.team;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.descriptions.SelectValues;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

/**
 * Sends Rundeck job notification messages to a Microsoft Teams channel
 * through a Teams Incoming Webhook.
 *
 * <p>Supports both webhook generations:</p>
 * <ul>
 *   <li>Office 365 Connector webhooks (MessageCard format, legacy) —
 *       retired by Microsoft in May 2026</li>
 *   <li>Workflows (Power Automate) webhooks (Adaptive Card format) —
 *       the supported replacement, chosen automatically by URL host</li>
 * </ul>
 */
@Plugin(service = "Notification", name = "TeamsNotification")
@PluginDescription(title = "Teams Incoming WebHook",
                   description = "Sends Rundeck Notifications to Microsoft Teams")
public class TeamNotificationPlugin implements NotificationPlugin {

    private static final String TEAM_MESSAGE_COLOR_GREEN = "good";
    private static final String TEAM_MESSAGE_COLOR_YELLOW = "warning";
    private static final String TEAM_MESSAGE_COLOR_RED = "danger";

    private static final String ADAPTIVE_TEMPLATE = "team-adaptive-message.ftl";
    private static final String CONNECTOR_TEMPLATE = "team-incoming-message.ftl";

    private static final String TRIGGER_START = "start";
    private static final String TRIGGER_SUCCESS = "success";
    private static final String TRIGGER_FAILURE = "failure";

    /** Workflows webhook URLs live under this host; they expect Adaptive Cards. */
    private static final String WORKFLOWS_HOST_SUFFIX = "webhook.office.com";

    private static final Configuration FREEMARKER_CFG = new Configuration(Configuration.VERSION_2_3_0);

    static {
        ClassTemplateLoader builtIn = new ClassTemplateLoader(TeamNotificationPlugin.class, "/templates");
        FREEMARKER_CFG.setTemplateLoader(new MultiTemplateLoader(new TemplateLoader[]{builtIn}));
        try {
            FREEMARKER_CFG.setSetting(Configuration.CACHE_STORAGE_KEY, "strong:20, soft:250");
        } catch (Exception e) {
            System.err.printf("Could not configure FreeMarker cache: %s%n", e.getMessage());
        }
    }

    @PluginProperty(title = "WebHook URL", description = "Teams Incoming WebHook URL", required = true)
    private String webhookUrl;

    /**
     * Optional override of the payload format. "auto" (default) picks Adaptive
     * Card for Workflows webhook URLs (webhook.office.com) and MessageCard
     * (connector format) otherwise.
     */
    @PluginProperty(title = "Message Format",
                    description = "Payload format: auto (default), adaptive (Workflows), or card (legacy connector)",
                    required = false,
                    defaultValue = "auto")
    @SelectValues(values = {"auto", "adaptive", "card"}, freeSelect = true)
    private String messageFormat;

    /**
     * Sends a message to a Microsoft Teams channel when a job notification event
     * is raised by Rundeck.
     *
     * @param trigger       name of job notification event causing notification
     * @param executionData job execution data
     * @param config        plugin configuration
     * @return true if the Teams webhook indicates the message was accepted
     */
    @Override
    public boolean postNotification(String trigger, Map executionData, Map config) {
        if (!TRIGGER_START.equals(trigger)
                && !TRIGGER_SUCCESS.equals(trigger)
                && !TRIGGER_FAILURE.equals(trigger)) {
            throw new IllegalArgumentException("Unknown trigger type: [" + trigger + "].");
        }

        String message = generateMessage(trigger, executionData, config);
        WebhookResult result = invokeTeamAPIMethod(webhookUrl, message);

        // Legacy O365 Connector webhooks answer 200 with body "1"; Workflows
        // webhooks answer 202 Accepted with an empty body. HTTP 2xx = accepted
        // for both. Ref:
        // https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/connectors-using
        if (result.statusCode >= 200 && result.statusCode < 300) {
            return true;
        }
        if (result.statusCode == 429) {
            throw new TeamNotificationPluginException(
                    "Microsoft Teams webhook rate limit hit (HTTP 429). Retry the notification; "
                    + "Teams allows roughly 4 requests per second per webhook.");
        }
        // Unfortunately there seems to be no way to obtain a reference to the
        // plugin logger within notification plugins, but throwing an exception
        // will result in its message being logged.
        throw new TeamNotificationPluginException(
                "Microsoft Teams webhook returned HTTP " + result.statusCode + ": "
                + summarize(result.responseBody));
    }

    /**
     * Truncated, single-line view of a response body for error messages —
     * avoids dumping full payloads into logs.
     */
    private static String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) {
            return "(empty response)";
        }
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }

    private String generateMessage(String trigger, Map executionData, Map config) {
        String color;
        if (TRIGGER_START.equals(trigger)) {
            color = TEAM_MESSAGE_COLOR_YELLOW;
        } else if (TRIGGER_SUCCESS.equals(trigger)) {
            color = TEAM_MESSAGE_COLOR_GREEN;
        } else {
            color = TEAM_MESSAGE_COLOR_RED;
        }

        Map<String, Object> model = new HashMap<String, Object>();
        model.put("trigger", trigger);
        model.put("color", color);
        model.put("executionData", executionData);
        model.put("config", config);

        StringWriter sw = new StringWriter();
        try {
            Template template = FREEMARKER_CFG.getTemplate(resolveTemplate());
            template.process(model, sw);
        } catch (IOException ioEx) {
            throw new TeamNotificationPluginException(
                    "Error loading Team notification message template: [" + ioEx.getMessage() + "].", ioEx);
        } catch (TemplateException templateEx) {
            throw new TeamNotificationPluginException(
                    "Error merging Team notification message template: [" + templateEx.getMessage() + "].", templateEx);
        }

        return sw.toString();
    }

    /**
     * Picks the card format: explicit setting wins; "auto" detects Workflows
     * webhooks by URL host and defaults everything else (including custom
     * proxies to connector URLs) to the Adaptive format which is the
     * maintained one; unknown values fall back to auto-resolution.
     */
    private String resolveTemplate() {
        String format = messageFormat == null ? "auto" : messageFormat.trim().toLowerCase();
        if ("adaptive".equals(format)) {
            return ADAPTIVE_TEMPLATE;
        }
        if ("card".equals(format)) {
            return CONNECTOR_TEMPLATE;
        }
        return isWorkflowsWebhook(webhookUrl) ? ADAPTIVE_TEMPLATE : CONNECTOR_TEMPLATE;
    }

    private boolean isWorkflowsWebhook(String url) {
        String host;
        try {
            host = new URI(url).getHost();
        } catch (Exception e) {
            return false;
        }
        return host != null && host.toLowerCase().endsWith(WORKFLOWS_HOST_SUFFIX);
    }

    private WebhookResult invokeTeamAPIMethod(String webhookUrl, String message) {
        URL requestUrl = toURL(webhookUrl);

        HttpURLConnection connection = null;
        InputStream responseStream = null;
        try {
            connection = openConnection(requestUrl);
            putRequestStream(connection, message);
            int status = connection.getResponseCode();
            responseStream = getResponseStream(connection);
            return new WebhookResult(status, getTeamResponse(responseStream));
        } catch (IOException ioEx) {
            throw new TeamNotificationPluginException(
                    "Error posting to Teams URL: [" + ioEx.getMessage() + "].", ioEx);
        } finally {
            closeQuietly(responseStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URL toURL(String url) {
        try {
            return new URI(url).toURL();
        } catch (Exception malformedURLEx) {
            throw new TeamNotificationPluginException(
                    "Team API URL is malformed: [" + malformedURLEx.getMessage() + "].", malformedURLEx);
        }
    }

    private HttpURLConnection openConnection(URL requestUrl) {
        try {
            String proxyHost = System.getProperty("http.proxyHost");
            String proxyPort = System.getProperty("http.proxyPort");
            String proxyUser = System.getProperty("http.proxyUser");
            String proxyPassword = System.getProperty("http.proxyPassword");

            HttpURLConnection conn;
            if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort)));
                conn = (HttpURLConnection) requestUrl.openConnection(proxy);
                if (proxyUser != null && !proxyUser.isEmpty()) {
                    String credentials = Base64.getEncoder().encodeToString(
                            (proxyUser + ":" + proxyPassword).getBytes(StandardCharsets.UTF_8));
                    conn.setRequestProperty("Proxy-Authorization", "Basic " + credentials);
                }
            } else {
                conn = (HttpURLConnection) requestUrl.openConnection();
            }

            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            return conn;
        } catch (IOException | NumberFormatException ioEx) {
            throw new TeamNotificationPluginException(
                    "Error opening connection to Teams URL: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void putRequestStream(HttpURLConnection connection, String message) {
        DataOutputStream wr = null;
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            connection.setDoInput(true);
            connection.setDoOutput(true);
            wr = new DataOutputStream(connection.getOutputStream());
            wr.write(message.getBytes(StandardCharsets.UTF_8));
            wr.flush();
        } catch (IOException ioEx) {
            throw new TeamNotificationPluginException(
                    "Error putting data to Teams URL: [" + ioEx.getMessage() + "].", ioEx);
        } finally {
            if (wr != null) {
                try {
                    wr.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private InputStream getResponseStream(HttpURLConnection connection) {
        InputStream input;
        try {
            input = connection.getInputStream();
        } catch (IOException ioEx) {
            input = connection.getErrorStream();
        }
        return input;
    }

    private String getTeamResponse(InputStream responseStream) {
        if (responseStream == null) {
            return "";
        }
        try {
            Scanner scanner = new Scanner(responseStream, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception ioEx) {
            throw new TeamNotificationPluginException(
                    "Error reading Team API JSON response: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ioEx) {
                // ignore
            }
        }
    }

    /** HTTP status + response body of a webhook POST. */
    private static final class WebhookResult {
        final int statusCode;
        final String responseBody;

        WebhookResult(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}