package mx.ipn.telemetria;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TelemetriaServerTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void servesFrontendControlsTelemetryAndExportsFilteredCsv() throws Exception {
        try (TelemetriaServer server = new TelemetriaServer(directory.resolve("api.sqlite"), "127.0.0.1", 0,
                new TelemetryService.Options(4210, 8080, 50, 20, 1000))) {
            server.start();
            int port = server.port();
            assertEquals(200, get(port, "/").statusCode());
            assertTrue(get(port, "/").body().contains("Monitoreo de telemetría"));
            assertTrue(get(port, "/app.js").headers().firstValue("Content-Type").orElse("").contains("javascript"));
            assertEquals(404, get(port, "/../pom.xml").statusCode());
            assertEquals(400, get(port, "/api/dashboard?mode=SQL").statusCode());
            String config = "{\"mode\":\"DEMO\",\"deviceIp\":\"192.168.1.101\",\"highThreshold\":32,\"jumpThreshold\":2}";
            assertEquals(403, post(port, "/api/start", config, "https://otro-sitio.example").statusCode());
            assertEquals(403, post(port, "/api/start", config, "null").statusCode());
            assertEquals(400, post(port, "/api/start", "{", null).statusCode());
            assertEquals(400, post(port, "/api/start", "{}", null).statusCode());
            assertEquals(200, post(port, "/api/start", config, "http://127.0.0.1:" + port).statusCode());
            assertEquals(409, post(port, "/api/start", config, null).statusCode());
            JsonObject state = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                state = JsonParser.parseString(get(port, "/api/dashboard?period=all").body()).getAsJsonObject();
                if (state.get("samples").getAsInt() >= 2) break;
                Thread.sleep(10);
            }
            assertNotNull(state);
            assertTrue(state.get("samples").getAsInt() >= 2);
            assertEquals(200, post(port, "/api/stop", "{}", null).statusCode());
            HttpResponse<String> csv = get(port, "/api/export.csv?mode=DEMO&period=all");
            assertEquals(200, csv.statusCode());
            assertTrue(csv.body().contains(",DEMO,"));
            assertTrue(csv.headers().firstValue("Content-Disposition").orElse("").contains("telemetria.csv"));
            assertFalse(get(port, "/api/export.csv?mode=UDP&period=all").body().contains(",DEMO,"));
            assertEquals(400, get(port, "/api/export.csv?mode=bad").statusCode());
            assertEquals(1, JsonParser.parseString(get(port, "/api/dashboard?page=999").body()).getAsJsonObject().getAsJsonObject("history").get("page").getAsInt());
            assertFalse(JsonParser.parseString(get(port, "/api/dashboard").body()).getAsJsonObject().get("running").getAsBoolean());
        }
    }
}
