package mx.ipn.telemetria;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/** Conserva el formato y las reglas de TelemetriaIoT.java. */
public final class Protocol {
    private Protocol() {}

    public record Config(String mode, String deviceIp, double highThreshold, double jumpThreshold) {
        public static Config defaults() { return new Config("UDP", "192.168.1.101", 32, 2); }

        public Config validated() {
            if (mode == null || !java.util.List.of("UDP", "TCP", "DEMO").contains(mode))
                throw new IllegalArgumentException("Selecciona UDP, TCP o Demostración.");
            if (deviceIp == null || !deviceIp.matches("(?:\\d{1,3}\\.){3}\\d{1,3}"))
                throw new IllegalArgumentException("Introduce una dirección IPv4 válida para el ESP32.");
            String[] parts = deviceIp.split("\\.");
            for (String part : parts) {
                if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0")))
                    throw new IllegalArgumentException("Introduce una dirección IPv4 válida para el ESP32.");
            }
            int first = Integer.parseInt(parts[0]);
            if (first == 0 || first >= 224) throw new IllegalArgumentException("Usa la dirección IPv4 del dispositivo, no una dirección de difusión.");
            if (!Double.isFinite(highThreshold) || highThreshold < -40 || highThreshold > 80)
                throw new IllegalArgumentException("El umbral de temperatura debe estar entre −40 y 80 °C.");
            if (!Double.isFinite(jumpThreshold) || jumpThreshold <= 0 || jumpThreshold > 50)
                throw new IllegalArgumentException("El aumento debe ser mayor que 0 y hasta 50 °C.");
            return this;
        }

        public static Config fromJson(JsonObject json) {
            try {
                for (String field : java.util.List.of("mode", "deviceIp", "highThreshold", "jumpThreshold")) {
                    if (!json.has(field) || !json.get(field).isJsonPrimitive()) throw new IllegalArgumentException();
                }
                if (!json.getAsJsonPrimitive("mode").isString() || !json.getAsJsonPrimitive("deviceIp").isString()
                        || !json.getAsJsonPrimitive("highThreshold").isNumber() || !json.getAsJsonPrimitive("jumpThreshold").isNumber())
                    throw new IllegalArgumentException();
                return new Config(json.get("mode").getAsString(), json.get("deviceIp").getAsString(),
                        json.get("highThreshold").getAsDouble(), json.get("jumpThreshold").getAsDouble()).validated();
            } catch (IllegalStateException | NullPointerException exception) {
                throw new IllegalArgumentException("Completa el modo, la IP y los umbrales numéricos.");
            }
        }
    }

    public record Sample(double temperature, double humidity, long sampleId) {}
    public record Evaluation(double temperature, double humidity, long sampleId, double delta,
                             int segments, boolean highAlert, boolean jumpAlert) {
        public boolean alert() { return highAlert || jumpAlert; }
    }

    public static Sample parse(String packet) {
        if (packet == null || packet.length() > 256) throw new IllegalArgumentException("Paquete demasiado grande o inválido.");
        Map<String, String> fields = new HashMap<>();
        for (String field : packet.trim().split(";")) {
            String[] pair = field.trim().split("=", -1);
            if (!java.util.List.of("TEMP", "HUM", "ID").contains(pair[0].trim())) continue;
            if (pair.length != 2 || fields.containsKey(pair[0].trim())) throw new IllegalArgumentException("Campos duplicados o inválidos.");
            fields.put(pair[0].trim(), pair[1].trim());
        }
        try {
            for (String key : java.util.List.of("TEMP", "HUM", "ID")) {
                if (!fields.containsKey(key) || fields.get(key).isEmpty()) throw new IllegalArgumentException("Faltan TEMP, HUM o ID.");
            }
            double t = Double.parseDouble(fields.get("TEMP"));
            double h = Double.parseDouble(fields.get("HUM"));
            if (!fields.get("ID").matches("\\d+")) throw new IllegalArgumentException("ID de muestra inválido.");
            long id = Long.parseLong(fields.get("ID"));
            if (!Double.isFinite(t) || t < -40 || t > 80 || !Double.isFinite(h) || h < 0 || h > 100 || id < 0 || id > 4294967295L)
                throw new IllegalArgumentException("Lectura inválida de temperatura, humedad o ID.");
            return new Sample(t, h, id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Lectura inválida de temperatura, humedad o ID.");
        }
    }

    public static int segments(double temperature) {
        if (temperature <= 20) return 1;
        if (temperature >= 40) return 8;
        return Math.max(1, Math.min(8, 1 + (int) ((temperature - 20) * 7 / 20)));
    }

    public static Evaluation evaluate(Sample sample, Sample previous, Config config) {
        double delta = previous == null ? 0 : Math.round((sample.temperature - previous.temperature) * 100) / 100.0;
        return new Evaluation(sample.temperature, sample.humidity, sample.sampleId, delta, segments(sample.temperature),
                sample.temperature >= config.highThreshold, previous != null && delta >= config.jumpThreshold);
    }
}
