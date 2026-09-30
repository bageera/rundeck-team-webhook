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
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
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
 * through a Teams Incoming Webhook (Office 365 Connector).
 */
@Plugin(service = "Notification", name = "TeamsNotification")
@PluginDescription(title = "Teams Incoming WebHook",
                   description = "Sends Rundeck Notifications to Microsoft Teams")
public class TeamNotificationPlugin implements NotificationPlugin {

    private static final String TEAM_MESSAGE_COLOR_GREEN = "good";
    private static final String TEAM_MESSAGE_COLOR_YELLOW = "warning";
    private static final String TEAM_MESSAGE_COLOR_RED = "danger";

    private static final String TEAM_MESSAGE_TEMPLATE = "team-incoming-message.ftl";

    private static final String TRIGGER_START = "start";
    private static final String TRIGGER_SUCCESS = "success";
    private static final String TRIGGER_FAILURE = "failure";

    private static final Configuration FREEMARKER_CFG = new Configuration(Configuration.VERSION_2_3_0);

    static {
        FREEMARKER_CFG.setClassForTemplateLoading(TeamNotificationPlugin.class, "/templates");
        try {
            FREEMARKER_CFG.setSetting(Configuration.CACHE_STORAGE_KEY, "strong:20, soft:250");
        } catch (Exception e) {
            System.err.printf("Could not configure FreeMarker cache: %s%n", e.getMessage());
        }
    }

    @PluginProperty(title = "WebHook URL", description = "Team Incoming WebHook URL", required = true)
    private String webhookUrl;

    /**
     * Sends a message to a Microsoft Teams channel when a job notification event
     * is raised by Rundeck.
     *
     * @param trigger       name of job notification event causing notification
     * @param executionData job execution data
     * @param config        plugin configuration
     * @return true if the Teams API response indicates the message was accepted
     */
    @Override
    public boolean postNotification(String trigger, Map executionData, Map config) {
        if (!TRIGGER_START.equals(trigger)
                && !TRIGGER_SUCCESS.equals(trigger)
                && !TRIGGER_FAILURE.equals(trigger)) {
            throw new IllegalArgumentException("Unknown trigger type: [" + trigger + "].");
        }

        String message = generateMessage(trigger, executionData, config);
        String teamResponse = invokeTeamAPIMethod(webhookUrl, message);

        // If the POST succeeds, the Teams WebHook API returns "1".
        // Ref: https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/connectors-creating
        if ("1".equals(teamResponse)) {
            return true;
        }
        // Unfortunately there seems to be no way to obtain a reference to the plugin
        // logger within notification plugins, but throwing an exception will result
        // in its message being logged.
        throw new TeamNotificationPluginException(
                "Unknown status returned from Microsoft Teams API: [" + teamResponse + "].");
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
            Template template = FREEMARKER_CFG.getTemplate(TEAM_MESSAGE_TEMPLATE);
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

    private String invokeTeamAPIMethod(String webhookUrl, String message) {
        URL requestUrl = toURL(webhookUrl);

        HttpURLConnection connection = null;
        InputStream responseStream = null;
        try {
            connection = openConnection(requestUrl);
            putRequestStream(connection, message);
            responseStream = getResponseStream(connection);
            return getTeamResponse(responseStream);
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
            return new Scanner(responseStream, "UTF-8").useDelimiter("\\A").next();
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
}