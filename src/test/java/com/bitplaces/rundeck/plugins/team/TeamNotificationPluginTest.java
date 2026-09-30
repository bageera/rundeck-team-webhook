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
    void unknownTriggerThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> plugin.postNotification("bogus", executionData("g", "j", "u", "1"), new HashMap<>()));
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
        HttpServer server = startServer(200, "{\"error\":\"bad\"}", body);
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
}