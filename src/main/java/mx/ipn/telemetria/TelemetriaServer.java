package mx.ipn.telemetria;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** API REST y servidor del frontend HTML. Ejecutar con Java 17 o posterior. */
public final class TelemetriaServer implements AutoCloseable {
    private final Database database;
    private final TelemetryService telemetry;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private boolean closed;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "/", "text/html; charset=utf-8", "/app.js", "text/javascript; charset=utf-8",
            "/styles.css", "text/css; charset=utf-8", "/icon.svg", "image/svg+xml");

    public TelemetriaServer(Path dbPath, String host, int port, TelemetryService.Options options) throws IOException, SQLException {
        database = new Database(dbPath);
        try {
            telemetry = new TelemetryService(database, options);
            server = HttpServer.create(new InetSocketAddress(host, port), 32);
        } catch (IOException | SQLException | RuntimeException exception) { database.close(); throw exception; }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private Map<String, String> parameters(URI uri) {
        Map<String, String> values = new HashMap<>();
        if (uri.getRawQuery() == null) return values;
        for (String item : uri.getRawQuery().split("&")) {
            String[] pair = item.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] content = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message == null || message.isBlank() ? "Solicitud inválida. Revisa la configuración." : message));
    }

    private boolean validOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true; // clientes locales, pruebas y herramientas de terminal
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getRawAuthority() != null
                    && uri.getRawAuthority().equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Host"));
        } catch (IllegalArgumentException exception) { return false; }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "same-origin");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, String> parameters = parameters(exchange.getRequestURI());
            if (method.equals("GET") && path.equals("/api/dashboard")) {
                Map<String, Object> data = new LinkedHashMap<>(telemetry.state());
                data.put("history", database.history(parameters));
                data.put("events", database.events());
                data.put("serverTime", Instant.now().toString());
                json(exchange, 200, data);
            } else if (method.equals("GET") && path.equals("/api/export.csv")) {
                database.validateFilters(parameters);
                headers.set("Content-Type", "text/csv; charset=utf-8");
                headers.set("Content-Disposition", "attachment; filename=\"telemetria.csv\"");
                exchange.sendResponseHeaders(200, 0);
                database.exportCsv(parameters, exchange.getResponseBody());
            } else if (method.equals("POST") && (path.equals("/api/start") || path.equals("/api/stop"))) {
                if (!validOrigin(exchange)) { error(exchange, 403, "Origen no permitido."); return; }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    error(exchange, 415, "Usa Content-Type: application/json."); return;
                }
                if (path.equals("/api/start")) {
                    byte[] bytes = exchange.getRequestBody().readNBytes(4097);
                    if (bytes.length > 4096) { error(exchange, 413, "Solicitud demasiado grande."); return; }
                    JsonElement input = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                    if (!input.isJsonObject()) throw new IllegalArgumentException("La configuración debe ser un objeto JSON.");
                    telemetry.start(Protocol.Config.fromJson(input.getAsJsonObject()));
                } else telemetry.stop();
                json(exchange, 200, telemetry.state());
            } else if (method.equals("GET") && CONTENT_TYPES.containsKey(path)) {
                String resource = "/web" + (path.equals("/") ? "/index.html" : path);
                try (InputStream input = TelemetriaServer.class.getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("Recurso web no encontrado. Compila el proyecto nuevamente.");
                    byte[] content = input.readAllBytes();
                    headers.set("Content-Type", CONTENT_TYPES.get(path));
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                }
            } else error(exchange, 404, "Ruta no encontrada.");
        } catch (JsonParseException | IllegalArgumentException exception) {
            if (exchange.getResponseCode() < 0) error(exchange, 400, exception instanceof JsonParseException ? "JSON inválido." : exception.getMessage());
        } catch (IllegalStateException exception) {
            if (exchange.getResponseCode() < 0) error(exchange, 409, exception.getMessage());
        } catch (SQLException exception) {
            System.err.println("Error SQLite: " + exception.getMessage());
            if (exchange.getResponseCode() < 0) error(exchange, 500, "No se pudo acceder a la base de datos.");
        } catch (IOException exception) {
            if (exchange.getResponseCode() < 0) error(exchange, 400, exception.getMessage());
        } finally { exchange.close(); }
    }

    @Override public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        server.stop(1);
        telemetry.close();
        executor.shutdownNow();
        database.close();
    }

    public static void main(String[] arguments) throws Exception {
        Path dbPath = Path.of(System.getenv().getOrDefault("DB_PATH", "data/telemetria.sqlite"));
        if (arguments.length > 0) {
            if (arguments.length != 1 || !arguments[0].equals("--init-db"))
                throw new IllegalArgumentException("Uso: java -jar telemetria-web.jar [--init-db]");
            try (Database database = new Database(dbPath)) {
                database.history(Map.of("period", "all"));
                System.out.println("SQLite preparado: " + dbPath.toAbsolutePath());
                System.out.println("Las tablas estan listas. El historial existente se conserva.");
            }
            return;
        }
        int port;
        try { port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000")); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("PORT debe ser un número."); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("PORT debe estar entre 1 y 65535.");
        TelemetriaServer app = new TelemetriaServer(dbPath, System.getenv().getOrDefault("HOST", "0.0.0.0"), port, TelemetryService.Options.defaults());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { app.close(); } catch (SQLException exception) { System.err.println(exception.getMessage()); }
        }, "telemetria-shutdown"));
        app.start();
        System.out.println("\nTelemetría IoT · http://localhost:" + port);
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address) System.out.println("Celular / red local: http://" + address.getHostAddress() + ":" + port);
            }
        }
        System.out.println("SQLite: " + dbPath.toAbsolutePath() + "\nCtrl+C para salir.\n");
    }
}
