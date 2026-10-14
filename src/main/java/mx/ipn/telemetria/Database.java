package mx.ipn.telemetria;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static mx.ipn.telemetria.Protocol.*;

/** Una conexión JDBC protegida para los hilos HTTP y de telemetría. */
public final class Database implements AutoCloseable {
    private final Connection connection;
    private final Gson gson = new Gson();
    private static final String SELECT_READINGS = """
        SELECT id, session_id AS sessionId, sample_id AS sampleId, received_at AS receivedAt,
        temperature, humidity, delta, segments, high_alert AS highAlert, jump_alert AS jumpAlert,
        mode, device_ip AS deviceIp FROM measurements
        """;

    public Database(Path path) throws SQLException, IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (InputStream input = Database.class.getResourceAsStream("/schema.sql")) {
            if (input == null) throw new IOException("No se encontró schema.sql.");
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
        }
        execute("UPDATE sessions SET stopped_at = ? WHERE stopped_at IS NULL", Instant.now().toString());
    }

    public synchronized Config config() throws SQLException {
        List<Map<String, Object>> rows = query("SELECT config FROM settings WHERE id = 1");
        return rows.isEmpty() ? Config.defaults() : gson.fromJson((String) rows.get(0).get("config"), Config.class).validated();
    }

    public synchronized void saveConfig(Config config) throws SQLException {
        execute("INSERT INTO settings (id, config) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET config = excluded.config", gson.toJson(config));
    }

    public synchronized void startSession(String id, Config config) throws SQLException {
        execute("INSERT INTO sessions (id, mode, device_ip, started_at, high_threshold, jump_threshold) VALUES (?, ?, ?, ?, ?, ?)",
                id, config.mode(), config.deviceIp(), Instant.now().toString(), config.highThreshold(), config.jumpThreshold());
    }

    public synchronized void stopSession(String id) throws SQLException {
        if (id != null) execute("UPDATE sessions SET stopped_at = ? WHERE id = ?", Instant.now().toString(), id);
    }

    public synchronized Map<String, Object> addSample(Evaluation sample, String sessionId, Config config) throws SQLException {
        // ISO con precisión fija permite ordenar y filtrar fechas como texto en SQLite.
        String receivedAt = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        execute("""
            INSERT INTO measurements (session_id, sample_id, received_at, temperature, humidity, delta,
            segments, high_alert, jump_alert, mode, device_ip) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, sessionId, sample.sampleId(), receivedAt, sample.temperature(), sample.humidity(), sample.delta(),
                sample.segments(), sample.highAlert() ? 1 : 0, sample.jumpAlert() ? 1 : 0, config.mode(), config.deviceIp());
        return query(SELECT_READINGS + " WHERE id = last_insert_rowid()").get(0);
    }

    public synchronized void event(String sessionId, String level, String message) throws SQLException {
        execute("INSERT INTO events (session_id, created_at, level, message) VALUES (?, ?, ?, ?)", sessionId, Instant.now().toString(), level, message);
    }

    public synchronized List<Map<String, Object>> events() throws SQLException {
        return query("SELECT id, created_at AS createdAt, level, message FROM events ORDER BY id DESC LIMIT 30");
    }

    private record Filter(String where, List<Object> values) {}
    public void validateFilters(Map<String, String> parameters) { filter(parameters); }

    private Filter filter(Map<String, String> parameters) {
        String mode = parameters.getOrDefault("mode", "all");
        String period = parameters.getOrDefault("period", "24h");
        if (!List.of("all", "UDP", "TCP", "DEMO").contains(mode) || !List.of("1h", "24h", "all").contains(period))
            throw new IllegalArgumentException("Filtro de historial inválido.");
        List<String> conditions = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (!mode.equals("all")) { conditions.add("mode = ?"); values.add(mode); }
        if (!period.equals("all")) {
            conditions.add("received_at >= ?");
            values.add(Instant.now().minusSeconds(period.equals("1h") ? 3600 : 86400).toString());
        }
        return new Filter(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), values);
    }

    public synchronized Map<String, Object> history(Map<String, String> parameters) throws SQLException {
        Filter filter = filter(parameters);
        int page;
        try { page = Integer.parseInt(parameters.getOrDefault("page", "1")); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Página inválida."); }
        if (page < 1 || page > 1000000) throw new IllegalArgumentException("Página inválida.");
        Map<String, Object> stats = query("""
            SELECT COUNT(*) AS count, MIN(temperature) AS min, MAX(temperature) AS max, AVG(temperature) AS average,
            COALESCE(SUM(CASE WHEN high_alert = 1 OR jump_alert = 1 THEN 1 ELSE 0 END), 0) AS alerts FROM measurements
            """ + filter.where, filter.values.toArray()).get(0);
        int pages = Math.max(1, (int) Math.ceil(((Number) stats.get("count")).doubleValue() / 10));
        page = Math.min(page, pages);
        List<Object> rowParams = new ArrayList<>(filter.values);
        rowParams.add((page - 1) * 10);
        List<Map<String, Object>> rows = query(SELECT_READINGS + filter.where + " ORDER BY id DESC LIMIT 10 OFFSET ?", rowParams.toArray());
        List<Map<String, Object>> chart = query(SELECT_READINGS + filter.where + " ORDER BY id DESC LIMIT 240", filter.values.toArray());
        Collections.reverse(chart);
        return Map.of("stats", stats, "page", page, "pages", pages, "rows", rows, "chart", chart);
    }

    public synchronized void exportCsv(Map<String, String> parameters, OutputStream output) throws SQLException, IOException {
        Filter filter = filter(parameters);
        output.write(("\uFEFFfecha_utc,modo,ip_dispositivo,sesion,id_muestra,temperatura_c,humedad_pct,aumento_c,segmentos,alerta_temperatura,alerta_aumento\r\n").getBytes(StandardCharsets.UTF_8));
        try (PreparedStatement statement = prepared("""
            SELECT received_at, mode, device_ip, session_id, sample_id, temperature, humidity, delta, segments,
            high_alert, jump_alert FROM measurements
            """ + filter.where + " ORDER BY id", filter.values.toArray()); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= 11; i++) cells.add(results.getString(i));
                output.write((String.join(",", cells) + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // Solo SQL fijo y parámetros enlazados; los filtros nunca se interpolan como SQL.
    private PreparedStatement prepared(String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        return statement;
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepared(sql, values)) { statement.executeUpdate(); }
    }

    private List<Map<String, Object>> query(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepared(sql, values); ResultSet results = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData metadata = results.getMetaData();
            while (results.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i), results.getObject(i));
                rows.add(row);
            }
            return rows;
        }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
