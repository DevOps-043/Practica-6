package mx.ipn.telemetria;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static mx.ipn.telemetria.Protocol.*;

class TelemetryServiceTest {
    @TempDir Path directory;

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "La condición no se cumplió antes del tiempo límite.");
    }

    static long count(TelemetryService service, String key) { return ((Number) service.state().get(key)).longValue(); }

    private void send(DatagramSocket sender, int port, String packet) throws IOException {
        byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);
        sender.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }

    @Test void udpStoresOnlyNewSamplesAndDetectsStaleSensorWithRepeatedPackets() throws Exception {
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0)) { port = reservation.getLocalPort(); }
        try (Database database = new Database(directory.resolve("udp.sqlite"));
             TelemetryService service = new TelemetryService(database, new TelemetryService.Options(port, 8080, 50, 50, 100));
             DatagramSocket sender = new DatagramSocket()) {
            service.start(new Config("UDP", "127.0.0.1", 32, 2));
            send(sender, port, "TEMP=25;HUM=50;ID=2000");
            await(() -> count(service, "samples") == 1);
            send(sender, port, "TEMP=25;HUM=50;ID=2000");
            send(sender, port, "TEMP=28;HUM=51;ID=4000");
            await(() -> count(service, "samples") == 2);
            assertEquals(true, service.state().get("activeAlert"));
            send(sender, port, "TEMP=28;HUM=51;ID=4000");
            await(() -> count(service, "packets") == 4);
            assertEquals(true, service.state().get("activeAlert"), "UDP conserva la alerta hasta la siguiente muestra nueva, como Swing.");
            send(sender, port, "TEMP=nan;HUM=51;ID=6000");
            await(() -> count(service, "invalidPackets") == 1);
            send(sender, port, "TEMP=20;HUM=51;ID=2000");
            await(() -> count(service, "packets") == 6);
            assertEquals(2, count(service, "samples"));
            assertTrue(service.state().get("lastError").toString().contains("reinicio"));
            await(() -> Boolean.FALSE.equals(service.state().get("fresh")));
            send(sender, port, "TEMP=28;HUM=51;ID=4000");
            await(() -> count(service, "packets") == 7);
            assertEquals(false, service.state().get("fresh"), "Los paquetes repetidos no deben refrescar la fecha de la muestra.");
            Map<?, ?> stats = (Map<?, ?>) database.history(Map.of("period", "all")).get("stats");
            assertEquals(2, ((Number) stats.get("count")).intValue());
            assertEquals(1, ((Number) stats.get("alerts")).intValue());
            service.stop();
            service.start(new Config("UDP", "127.0.0.1", 32, 2));
            send(sender, port, "TEMP=24;HUM=50;ID=2000");
            await(() -> count(service, "samples") == 1);
            assertEquals(3, ((Number) ((Map<?, ?>) database.history(Map.of("period", "all")).get("stats")).get("count")).intValue());
        }
    }

    @Test void refusesOccupiedUdpPortWithoutLeavingAnActiveSession() throws Exception {
        try (DatagramSocket occupied = new DatagramSocket(0);
             Database database = new Database(directory.resolve("occupied.sqlite"));
             TelemetryService service = new TelemetryService(database, new TelemetryService.Options(occupied.getLocalPort(), 8080, 50, 50, 100))) {
            IOException error = assertThrows(IOException.class, () -> service.start(new Config("UDP", "127.0.0.1", 32, 2)));
            assertTrue(error.getMessage().contains("ocupado"));
            assertEquals(false, service.state().get("running"));
        }
    }

    @Test void tcpUsesGetTempAndReturnsAlertCommandsOnTheSameSocketIncludingStop() throws Exception {
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        AtomicInteger requests = new AtomicInteger();
        try (ServerSocket esp32 = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             Database database = new Database(directory.resolve("tcp.sqlite"));
             TelemetryService service = new TelemetryService(database, new TelemetryService.Options(4210, esp32.getLocalPort(), 100, 50, 1000))) {
            Thread simulator = new Thread(() -> {
                while (!esp32.isClosed()) {
                    try (Socket socket = esp32.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        if (!"GET_TEMP".equals(input.readLine())) { commands.add("BAD_REQUEST"); continue; }
                        int index = requests.incrementAndGet();
                        OutputStream output = socket.getOutputStream();
                        String[] packets = {"TEMP=25;HUM=55;ID=2000", "TEMP=28;HUM=55;ID=4000", "TEMP=28;HUM=55;ID=4000", "TEMP=33;HUM=55;ID=6000"};
                        String packet = packets[Math.min(index - 1, packets.length - 1)];
                        int split = packet.indexOf(';') + 1;
                        output.write(packet.substring(0, split).getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        output.write((packet.substring(split) + "\r\n").getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        String command = input.readLine();
                        if (command != null) commands.add(command);
                    } catch (IOException ignored) { /* cierre del simulador */ }
                }
            });
            simulator.setDaemon(true);
            simulator.start();
            service.start(new Config("TCP", "127.0.0.1", 32, 2));
            assertEquals("ALERTA_OFF", commands.poll(5, TimeUnit.SECONDS));
            assertEquals("ALERTA_ON", commands.poll(5, TimeUnit.SECONDS));
            assertEquals("ALERTA_OFF", commands.poll(5, TimeUnit.SECONDS), "TCP no repite la alerta de salto para el mismo ID.");
            assertEquals("ALERTA_ON", commands.poll(5, TimeUnit.SECONDS));
            assertEquals(3, count(service, "samples"));
            service.stop();
            assertEquals("ALERTA_OFF", commands.poll(5, TimeUnit.SECONDS));
            assertEquals(false, service.state().get("running"));
        }
    }

    @Test void demoAndSettingsPersistAcrossDatabaseReopenWithoutAutostart() throws Exception {
        Path filename = directory.resolve("persistent.sqlite");
        int saved;
        try (Database database = new Database(filename);
             TelemetryService service = new TelemetryService(database, new TelemetryService.Options(4210, 8080, 50, 20, 100))) {
            service.start(new Config("DEMO", "192.168.1.101", 31, 3));
            await(() -> count(service, "samples") >= 2);
            service.stop();
            saved = ((Number) ((Map<?, ?>) database.history(Map.of("period", "all")).get("stats")).get("count")).intValue();
        }
        try (Database reopened = new Database(filename);
             TelemetryService service = new TelemetryService(reopened, TelemetryService.Options.defaults())) {
            assertEquals("DEMO", reopened.config().mode());
            assertEquals(31, reopened.config().highThreshold());
            assertEquals(false, service.state().get("running"));
            assertNull(service.state().get("latest"));
            assertEquals(saved, ((Number) ((Map<?, ?>) reopened.history(Map.of("period", "all")).get("stats")).get("count")).intValue());
            assertEquals(0, ((Number) ((Map<?, ?>) reopened.history(Map.of("mode", "UDP", "period", "all")).get("stats")).get("count")).intValue());
        }
    }
}
