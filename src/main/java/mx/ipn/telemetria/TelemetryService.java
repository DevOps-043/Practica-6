package mx.ipn.telemetria;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static mx.ipn.telemetria.Protocol.*;

/** Puente ESP32 -> Java -> SQLite -> navegador. Una sesión compartida por PC y móvil. */
public final class TelemetryService implements AutoCloseable {
    public record Options(int udpPort, int tcpPort, long tcpInterval, long demoInterval, long staleMs) {
        public static Options defaults() { return new Options(4210, 8080, 5000, 2000, 15000); }
    }

    private final Database database;
    private final Options options;
    private Config config;
    private volatile boolean running;
    private volatile long generation;
    private DatagramSocket udp;
    private Socket tcp;
    private Thread worker;
    private String sessionId;
    private Map<String, Object> latest;
    private Sample previous;
    private long packets, invalidPackets, samples, lastWarningAt;
    private String lastPacketAt, lastError;
    private boolean activeAlert;

    public TelemetryService(Database database, Options options) throws SQLException {
        this.database = database;
        this.options = options;
        this.config = database.config();
    }

    public synchronized Map<String, Object> state() {
        boolean fresh = running && latest != null
                && System.currentTimeMillis() - Instant.parse((String) latest.get("receivedAt")).toEpochMilli() < options.staleMs();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("config", config);
        state.put("running", running);
        state.put("fresh", fresh);
        state.put("latest", latest);
        state.put("activeAlert", activeAlert);
        state.put("packets", packets);
        state.put("invalidPackets", invalidPackets);
        state.put("samples", samples);
        state.put("lastPacketAt", lastPacketAt);
        state.put("lastError", lastError);
        state.put("status", !running ? "Detenido" : fresh ? (config.mode().equals("DEMO") ? "Demostración activa" : "Recibiendo datos")
                : latest == null ? "Esperando datos" : "Sin muestras nuevas");
        return state;
    }

    private void log(String level, String message) {
        try { database.event(sessionId, level, message); }
        catch (SQLException exception) { System.err.println("No se pudo guardar un evento: " + exception.getMessage()); }
    }

    private synchronized void warning(String message) {
        lastError = message;
        if (System.currentTimeMillis() - lastWarningAt > 5000) {
            log("warning", message);
            lastWarningAt = System.currentTimeMillis();
        }
    }

    public synchronized void start(Config requested) throws IOException, SQLException {
        if (running) throw new IllegalStateException("Ya hay un monitoreo activo. Detén la sesión para cambiar la configuración.");
        requested.validated();
        DatagramSocket newUdp = null;
        try {
            // Reservar el puerto antes de registrar una sesión.
            if (requested.mode().equals("UDP")) {
                try { newUdp = new DatagramSocket(options.udpPort()); }
                catch (BindException exception) { throw new IOException("El puerto UDP " + options.udpPort() + " está ocupado. Cierra Java Swing u otro monitor."); }
                newUdp.setSoTimeout(1000);
            }
            database.saveConfig(requested);
            String newSession = UUID.randomUUID().toString();
            database.startSession(newSession, requested);
            config = requested;
            sessionId = newSession;
            udp = newUdp;
            latest = null;
            previous = null;
            activeAlert = false;
            packets = invalidPackets = samples = lastWarningAt = 0;
            lastPacketAt = lastError = null;
            running = true;
            long epoch = ++generation;
            log("info", config.mode().equals("DEMO") ? "Demostración iniciada. Los datos simulados se guardan con origen DEMO."
                    : "Monitoreo " + config.mode() + " iniciado para ESP32 " + config.deviceIp() + ".");
            worker = new Thread(() -> run(epoch), "ESP32-" + config.mode());
            worker.setDaemon(true);
            worker.start();
        } catch (IOException | SQLException exception) {
            if (newUdp != null) newUdp.close();
            throw exception;
        }
    }

    private boolean active(long epoch) { return running && generation == epoch; }

    private void run(long epoch) {
        try {
            if (config.mode().equals("UDP")) runUdp(epoch);
            else if (config.mode().equals("TCP")) runTcp(epoch);
            else runDemo(epoch);
        } catch (Exception exception) {
            synchronized (this) {
                if (active(epoch)) {
                    lastError = "Monitoreo interrumpido: " + exception.getMessage();
                    log("error", lastError);
                    try { stop(); } catch (SQLException failure) { System.err.println(failure.getMessage()); }
                }
            }
        }
    }

    private void runUdp(long epoch) throws IOException {
        DatagramSocket socket = udp;
        String expectedIp = config.deviceIp();
        while (active(epoch)) {
            byte[] buffer = new byte[257];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try { socket.receive(packet); }
            catch (SocketTimeoutException exception) { continue; }
            catch (SocketException exception) { if (!active(epoch)) return; throw exception; }
            if (!packet.getAddress().getHostAddress().equals(expectedIp)) continue;
            synchronized (this) {
                if (!active(epoch)) return;
                packets++;
                try { receive(Protocol.parse(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8)), false); }
                catch (IllegalArgumentException exception) { invalidPackets++; warning("Paquete ignorado: " + exception.getMessage()); }
                catch (SQLException exception) { throw new IOException("No se pudo guardar la lectura.", exception); }
            }
        }
    }

    private synchronized boolean receive(Sample sample, boolean repeatedTcp) throws SQLException {
        lastPacketAt = Instant.now().toString();
        lastError = null;
        if (previous != null && sample.sampleId() == previous.sampleId()) {
            // UDP en Java ignora duplicados; TCP debe responder cada consulta.
            if (!repeatedTcp) return activeAlert;
            boolean high = sample.temperature() >= config.highThreshold();
            if (activeAlert && !high) log("info", "Temperatura normal: alerta finalizada.");
            activeAlert = high;
            return high;
        }
        if (previous != null && sample.sampleId() < previous.sampleId()) {
            warning("ID anterior: posible reinicio del ESP32. Detén e inicia el monitoreo para abrir una nueva sesión.");
            return false;
        }
        Evaluation evaluated = Protocol.evaluate(sample, previous, config);
        latest = database.addSample(evaluated, sessionId, config);
        previous = sample;
        samples++;
        if (evaluated.jumpAlert()) log("warning", String.format(Locale.US, "Incremento abrupto: +%.1f °C entre muestras.", evaluated.delta()));
        if (evaluated.highAlert() && !activeAlert) log("warning", String.format(Locale.US, "Temperatura de %.1f °C: umbral de %.1f °C alcanzado.", evaluated.temperature(), config.highThreshold()));
        if (!evaluated.alert() && activeAlert) log("info", "Temperatura normal: alerta finalizada.");
        activeAlert = evaluated.alert();
        return activeAlert;
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int count = 0; count <= 256; count++) {
            int value = input.read();
            if (value < 0) throw new IOException("El ESP32 cerró la conexión sin responder.");
            if (value == '\n') return buffer.toString(StandardCharsets.UTF_8).trim();
            buffer.write(value);
        }
        throw new IOException("Respuesta TCP demasiado grande.");
    }

    private void exchange(long epoch, boolean shutdown) throws IOException, SQLException {
        Socket socket = new Socket();
        Config current;
        synchronized (this) {
            if (!shutdown && !active(epoch)) { socket.close(); return; }
            current = config;
            if (!shutdown) tcp = socket;
        }
        try (socket) {
            socket.connect(new InetSocketAddress(current.deviceIp(), options.tcpPort()), 2500);
            socket.setSoTimeout(2500);
            OutputStream output = socket.getOutputStream();
            output.write("GET_TEMP\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            String packet = readLine(socket.getInputStream());
            synchronized (this) {
                boolean alert = false;
                if (!shutdown && active(epoch)) { packets++; alert = receive(Protocol.parse(packet), true); }
                output.write((alert ? "ALERTA_ON\n" : "ALERTA_OFF\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } finally {
            synchronized (this) { if (tcp == socket) tcp = null; }
        }
    }

    private void runTcp(long epoch) throws InterruptedException {
        while (active(epoch)) {
            try { exchange(epoch, false); }
            catch (IOException | IllegalArgumentException exception) { if (active(epoch)) warning("TCP: " + exception.getMessage()); }
            catch (SQLException exception) { throw new IllegalStateException("No se pudo guardar la lectura.", exception); }
            if (active(epoch)) Thread.sleep(options.tcpInterval());
        }
    }

    private void runDemo(long epoch) throws SQLException, InterruptedException {
        double[] values = {24.5, 24.8, 25.1, 25.5, 28.2, 28.5, 30.1, 32.6, 33.1, 31.0, 28.8, 26.3};
        long index = 0;
        while (active(epoch)) {
            synchronized (this) {
                if (!active(epoch)) return;
                packets++;
                receive(new Sample(values[(int) (index % values.length)], 52 + index % 8, ++index * 2000), false);
            }
            Thread.sleep(options.demoInterval());
        }
    }

    public synchronized void stop() throws SQLException {
        if (!running) return;
        boolean wasTcp = config.mode().equals("TCP");
        running = false;
        generation++;
        if (udp != null) { udp.close(); udp = null; }
        if (tcp != null) { try { tcp.close(); } catch (IOException ignored) {} tcp = null; }
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        if (wasTcp) {
            try {
                exchange(generation, true);
                log("info", "Se envió ALERTA_OFF al ESP32 al detener el monitoreo.");
            } catch (IOException exception) {
                lastError = "No se pudo enviar ALERTA_OFF. Revisa el ESP32 o cambia el DIP a UDP para desactivar el estrobo.";
                log("warning", lastError);
            }
        }
        database.stopSession(sessionId);
        log("info", "Monitoreo detenido. El historial permanece guardado.");
        activeAlert = false;
    }

    @Override public void close() throws SQLException { stop(); }
}
