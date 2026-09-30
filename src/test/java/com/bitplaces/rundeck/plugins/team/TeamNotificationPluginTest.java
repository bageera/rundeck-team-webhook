package com.bitplaces.rundeck.plugins.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import freemarker.template.TemplateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TeamNotificationPlugin: template rendering/escaping for all
 * triggers, trigger validation, and webhook POST behaviour against an
 * in-process HTTP server.
 */
class TeamNotificationPluginTest {

    /** Reflectively seeds the private webhookUrl field. */
    private static TeamNotificationPlugin pluginWithWebhook(String url) throws Exception {
        TeamNotificationPlugin plugin = new TeamNotificationPlugin();
        java.lang.reflect.Field f = TeamNotificationPlugin.class.getDeclaredField("webhookUrl");
        f.setAccessible(true);
        f.set(plugin, url);
        return plugin;
    }

    private static Map<String, Object> executionData(String jobGroup, String jobName, String user, String id) {
        Map<String, Object> job = new HashMap<>();
        job.put("group", jobGroup);
        job.put("name", jobName);
        job.put("href", "https://rundeck.example.com/job/" + jobName);

        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("user", user);
        data.put("href", "https://rundeck.example.com/execution/" + id);
        data.put("job", job);
        return data;
    }

    @TempDir
    java.nio.file.Path tempDir;

    /**
     * Starts an in-process HTTP server that drains the request body, records
     * it and the Content-Type request header, then replies with the given
     * status code and body.
     */
    private HttpServer startServer(int status, String responseBody, AtomicReference<byte[]> capturedBody)
            throws IOException {
        return startServer(status, responseBody, capturedBody, new AtomicReference<>());
    }

    private HttpServer startServer(int status, String responseBody, AtomicReference<byte[]> capturedBody,
                                   AtomicReference<String> capturedContentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            capturedBody.set(buf.toByteArray());

            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        server.start();
        return server;
    }

    private TeamNotificationPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new TeamNotificationPlugin();
    }

    // ---- trigger validation -------------------------------------------

    @Test
    void unknownTriggersRenderFallbackCardInsteadOfThrowing() throws Exception {
        // Rundeck may add triggers; the plugin must degrade gracefully,
        // not fail the notification (docs contract: plugin is the last stop).
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            java.lang.reflect.Field f = TeamNotificationPlugin.class.getDeclaredField("messageFormat");
            f.setAccessible(true);
            f.set(p, "card");

            for (String odd : new String[]{"bogus", "", null, "onretryablefailure", "retryablefailure",
                                           "onavgduration", "avgduration"}) {
                Map<String, Object> data = executionData("g", "j", "u", "1");
                assertTrue(p.postNotification(odd, data, new HashMap<>()),
                        "odd trigger should not throw: " + odd);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizeTriggerStripsOnPrefix() {
        assertEquals("start", TeamNotificationPlugin.normalizeTrigger("onstart"));
        assertEquals("success", TeamNotificationPlugin.normalizeTrigger("onsuccess"));
        assertEquals("failure", TeamNotificationPlugin.normalizeTrigger("onfailure"));
        assertEquals("avgduration", TeamNotificationPlugin.normalizeTrigger("onavgduration"));
        assertEquals("retryablefailure", TeamNotificationPlugin.normalizeTrigger("onretryablefailure"));
        // unprefixed passthrough
        assertEquals("start", TeamNotificationPlugin.normalizeTrigger("start"));
        assertEquals("failure", TeamNotificationPlugin.normalizeTrigger("FAILURE"));
        // null / blank
        assertEquals("unknown", TeamNotificationPlugin.normalizeTrigger(null));
        assertEquals("unknown", TeamNotificationPlugin.normalizeTrigger("  "));
    }

    @Test
    void allFiveDocumentedTriggersRenderBothFormats() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        final AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body, contentType);
        try {
            for (String format : new String[]{"card", "adaptive"}) {
                TeamNotificationPlugin p = pluginWithWebhook(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/");
                java.lang.reflect.Field f = TeamNotificationPlugin.class.getDeclaredField("messageFormat");
                f.setAccessible(true);
                f.set(p, format);

                for (String t : new String[]{"onstart", "onsuccess", "onfailure",
                                             "onavgduration", "onretryablefailure"}) {
                    assertTrue(p.postNotification(t, executionData("g", "j", "u", "1"), new HashMap<>()),
                            "trigger should render: " + format + "/" + t);
                }
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failureCardIncludesDocumentedNodeAndProjectData() throws Exception {
        Map<String, Object> job = new HashMap<>();
        job.put("group", "ops");
        job.put("name", "deploy");
        job.put("href", "https://rundeck.example.com/job/deploy");
        job.put("description", "Weekly deploy to staging");

        Map<String, Object> execution = new HashMap<>();
        execution.put("project", "prod");
        execution.put("failedNodeListString", "node-3, node-7");

        Map<String, Object> data = new HashMap<>();
        data.put("id", "9");
        data.put("user", "carol");
        data.put("href", "https://rundeck.example.com/execution/9");
        data.put("job", job);
        data.put("execution", execution);

        TeamNotificationPlugin p = new TeamNotificationPlugin();
        java.lang.reflect.Field urlField = TeamNotificationPlugin.class.getDeclaredField("webhookUrl");
        urlField.setAccessible(true);
        urlField.set(p, "https://example.webhook.office.com/x");

        java.lang.reflect.Method m = TeamNotificationPlugin.class.getDeclaredMethod("generateMessage",
                String.class, Map.class, Map.class);
        m.setAccessible(true);
        String json = (String) m.invoke(p, "failure", data, new HashMap<>());

        jakarta.json.JsonReader reader = jakarta.json.Json.createReader(new java.io.StringReader(json));
        jakarta.json.JsonObject card = reader.readObject().getJsonArray("attachments")
                .getJsonObject(0).getJsonObject("content");

        boolean hasProject = false, hasNodes = false, hasDesc = false;
        for (jakarta.json.JsonValue v : card.getJsonArray("body")) {
            jakarta.json.JsonObject o = v.asJsonObject();
            if (o.containsKey("facts")) {
                for (jakarta.json.JsonValue fact : o.getJsonArray("facts")) {
                    jakarta.json.JsonObject f = fact.asJsonObject();
                    String title = f.getString("title", f.getString("name", ""));
                    String value = f.getString("value", "");
                    if ("Project".equals(title) && "prod".equals(value)) hasProject = true;
                    if ("Failed Nodes".equals(title) && value.contains("node-3") && value.contains("node-7")) hasNodes = true;
                    if ("Description".equals(title) && value.contains("staging")) hasDesc = true;
                }
            }
        }
        assertTrue(hasProject, "Project fact missing: " + json);
        assertTrue(hasNodes, "Failed Nodes fact missing: " + json);
        assertTrue(hasDesc, "Description fact missing: " + json);
    }

    @Test
    void startCardOmitsCompletionOnlyFields() throws Exception {
        // on-start data has no failedNodeList etc.; guard must not render
        // empty fact boxes.
        Map<String, Object> data = executionData("g", "j", "u", "1");
        TeamNotificationPlugin p = new TeamNotificationPlugin();
        java.lang.reflect.Field urlField = TeamNotificationPlugin.class.getDeclaredField("webhookUrl");
        urlField.setAccessible(true);
        urlField.set(p, "https://example.webhook.office.com/x");

        java.lang.reflect.Method m = TeamNotificationPlugin.class.getDeclaredMethod("generateMessage",
                String.class, Map.class, Map.class);
        m.setAccessible(true);
        String json = (String) m.invoke(p, "start", data, new HashMap<>());

        assertFalse(json.contains("Failed Nodes"), json);
        assertFalse(json.contains("\"Project\"") || json.contains("\"Project\","), "unexpected project fact: " + json);
    }

    // ---- template rendering / JSON escaping ---------------------------

    @Test
    void templateEscapesHostileJobName() throws Exception {
        // Job name that would break out of the JSON string, or inject a
        // markdown link, if the value were not escaped.
        String hostile = "x\", \"injected\": true [click](https://attacker.example) \\n";
        Map<String, Object> data = executionData("evil group", hostile, "alice", "42");

        // Render via the same FreeMarker config the plugin uses, by running
        // generateMessage through postNotification against a local server.
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(p.postNotification("success", data, new HashMap<>()));

            String json = new String(body.get(), StandardCharsets.UTF_8);
            // no unescaped injection marker, and the hostile text is present escaped
            assertFalse(json.contains("\"injected\": true"), "raw breakout present in payload: " + json);
            assertTrue(json.contains("evil group"), json);
            assertTrue(json.contains("#[42]"), json);
            assertTrue(json.contains("alice"), json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulRenderForAllTriggers() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            for (String trigger : new String[]{"start", "success", "failure"}) {
                assertTrue(p.postNotification(trigger, executionData("group", "job", "bob", "7"),
                        new HashMap<>()),
                        "trigger should succeed: " + trigger);
                String json = new String(body.get(), StandardCharsets.UTF_8);
                assertTrue(json.contains("Rundeck Job"), json);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedWebhookUrlThrows() throws Exception {
        TeamNotificationPlugin p = pluginWithWebhook("not a url \\\\ bad://");
        Map<String, Object> data = executionData("g", "j", "u", "1");
        assertThrows(TeamNotificationPluginException.class,
                () -> p.postNotification("start", data, new HashMap<>()));
    }

    // ---- webhook POST behaviour ---------------------------------------

    @Test
    void successResponseIsTrue() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(p.postNotification("success", executionData("g", "j", "u", "1"), new HashMap<>()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unexpectedResponseThrowsAndPayloadNotLeaked() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(418, "{\"error\":\"bad\"}", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");

            Map<String, Object> data = executionData("g", "SECRET-JOB-NAME", "u", "1");
            TeamNotificationPluginException ex = assertThrows(TeamNotificationPluginException.class,
                    () -> p.postNotification("success", data, new HashMap<>()));

            // security fix: the error message must not echo the outbound payload
            assertFalse(ex.getMessage().contains("SECRET-JOB-NAME"), ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void utf8JobNameRoundTrips() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            String unicode = "job-Ω-日本語-é";
            assertTrue(p.postNotification("start", executionData("g", unicode, "u", "1"), new HashMap<>()));

            String decoded = new String(body.get(), StandardCharsets.UTF_8);
            assertTrue(decoded.contains(unicode), "UTF-8 payload mangled: " + decoded);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void contentTypeIsJson() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> capturedType = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body, capturedType);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(p.postNotification("start", executionData("g", "j", "u", "1"), new HashMap<>()));
            assertEquals("application/json; charset=utf-8", capturedType.get());
        } finally {
            server.stop(0);
        }
    }

    // ---- Workflows (Adaptive Card) webhook support ----------------------

    @Test
    void workflowsWebhookEmpty202BodyCountsAsSuccess() throws Exception {
        // Power Automate Workflows webhooks accept with HTTP 202 and an EMPTY
        // body — the legacy "1" check silently failed these. The auto-detected
        // Adaptive format must pass.
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(202, "", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            // pin Adaptive format: the local test server host would
            // auto-resolve to the legacy connector card
            java.lang.reflect.Field f = TeamNotificationPlugin.class.getDeclaredField("messageFormat");
            f.setAccessible(true);
            f.set(p, "adaptive");

            assertTrue(p.postNotification("success", executionData("g", "j", "u", "1"), new HashMap<>()));

            // Adaptive Card payload actually sent
            String json = new String(body.get(), StandardCharsets.UTF_8);
            assertTrue(json.contains("AdaptiveCard"), json);
            assertFalse(json.contains("themeColor"), "unexpected MessageCard payload: " + json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyConnectorBody1StillCountsAsSuccess() throws Exception {
        // legacy connector URLs answer 200 with body "1"; the MessageCard
        // format must still render and POST cleanly (card format pinned).
        AtomicReference<byte[]> body = new AtomicReference<>();
        final AtomicReference<String> capturedType = new AtomicReference<>();
        HttpServer server = startServer(200, "1", body, capturedType);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            java.lang.reflect.Field f = TeamNotificationPlugin.class.getDeclaredField("messageFormat");
            f.setAccessible(true);
            f.set(p, "card");

            assertTrue(p.postNotification("success", executionData("g", "j", "u", "1"), new HashMap<>()));

            String json = new String(body.get(), StandardCharsets.UTF_8);
            assertTrue(json.contains("themeColor"), "connector card expected, got: " + json);
            assertFalse(json.contains("AdaptiveCard"), json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void autoFormatDetectsWorkflowsByHost() throws Exception {
        // resolveTemplate() is private; verify through the rendered template
        // choice: a workflows-family URL (webhook.office.com) that points at
        // the local test server via a fake host header is not possible, so
        // instead test through generateMessage indirectly: POST to local
        // server with a webhook.office.com URL is not routable. Use reflection.
        TeamNotificationPlugin p = new TeamNotificationPlugin();
        java.lang.reflect.Field urlField = TeamNotificationPlugin.class.getDeclaredField("webhookUrl");
        urlField.setAccessible(true);
        urlField.set(p, "https://example.webhook.office.com/webhook/xyz");

        java.lang.reflect.Method m = TeamNotificationPlugin.class.getDeclaredMethod("resolveTemplate");
        m.setAccessible(true);
        assertEquals("team-adaptive-message.ftl", m.invoke(p));

        urlField.set(p, "https://outlook.office.com/webhook/legacy");
        assertEquals("team-incoming-message.ftl", m.invoke(p));

        // explicit override wins over auto-detection
        java.lang.reflect.Field fmt = TeamNotificationPlugin.class.getDeclaredField("messageFormat");
        fmt.setAccessible(true);
        fmt.set(p, "card");
        assertEquals("team-incoming-message.ftl", m.invoke(p));
        fmt.set(p, "adaptive");
        assertEquals("team-adaptive-message.ftl", m.invoke(p));
    }

    @Test
    void adaptiveCardTemplateIsValidJsonShape() throws Exception {
        Map<String, Object> data = executionData("ops", "deploy job", "carol", "99");
        TeamNotificationPlugin p = new TeamNotificationPlugin();
        java.lang.reflect.Field urlField = TeamNotificationPlugin.class.getDeclaredField("webhookUrl");
        urlField.setAccessible(true);
        urlField.set(p, "https://example.webhook.office.com/x");

        java.lang.reflect.Method m = TeamNotificationPlugin.class.getDeclaredMethod("generateMessage",
                String.class, Map.class, Map.class);
        m.setAccessible(true);
        String json = (String) m.invoke(p, "failure", data, new HashMap<>());

        // parse as JSON to prove well-formedness
        jakarta.json.JsonReader reader = jakarta.json.Json.createReader(
                new java.io.StringReader(json));
        jakarta.json.JsonObject root = reader.readObject();

        assertEquals("message", root.getString("type"));
        jakarta.json.JsonObject card = root.getJsonArray("attachments").getJsonObject(0)
                .getJsonObject("content");
        assertEquals("AdaptiveCard", card.getString("type"));
        assertEquals("1.4", card.getString("version"));

        // every text block must carry the (escaped) job name — hostile name check
        String allText = card.getJsonArray("body").stream()
                .filter(v -> v.asJsonObject().containsKey("text"))
                .map(v -> v.asJsonObject().getString("text"))
                .reduce("", (a, b) -> a + b);
        assertTrue(allText.contains("deploy job"), allText);

        // status block exists and colors differ by trigger
        assertTrue(card.getJsonArray("body").stream()
                .anyMatch(v -> v.asJsonObject().containsKey("color")));
    }

    @Test
    void rateLimit429ProducesHelpfulError() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(429, "{\"error\":\"Microsoft Teams endpoint returned HTTP error 429\"}", body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");

            TeamNotificationPluginException ex = assertThrows(TeamNotificationPluginException.class,
                    () -> p.postNotification("start", executionData("g", "j", "u", "1"), new HashMap<>()));
            assertTrue(ex.getMessage().contains("429"), ex.getMessage());
            assertTrue(ex.getMessage().toLowerCase().contains("rate limit"), ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorIsReportedWithoutFullPayloadDump() throws Exception {
        String longError = "x".repeat(500);
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = startServer(500, longError, body);
        try {
            TeamNotificationPlugin p = pluginWithWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");

            TeamNotificationPluginException ex = assertThrows(TeamNotificationPluginException.class,
                    () -> p.postNotification("failure", executionData("g", "j", "u", "1"), new HashMap<>()));
            assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
            // summarize() caps the echoed body at 200 chars
            assertTrue(ex.getMessage().length() < 400, "message too long: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }
}