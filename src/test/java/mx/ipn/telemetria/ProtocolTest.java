package mx.ipn.telemetria;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static mx.ipn.telemetria.Protocol.*;

class ProtocolTest {
    @Test void readsTheOriginalEsp32Packet() {
        Sample sample = Protocol.parse("TEMP=25.4;HUM=58.0;ID=2000\r\n");
        assertEquals(25.4, sample.temperature());
        assertEquals(58, sample.humidity());
        assertEquals(2000, sample.sampleId());
    }

    @Test void rejectsMissingNonFiniteAndMalformedReadings() {
        for (String value : new String[]{"TEMP=nan;HUM=50;ID=1", "TEMP=Infinity;HUM=50;ID=1", "TEMP=;HUM=50;ID=1",
                "TEMP=25;ID=1", "TEMP=25;HUM=101;ID=1", "TEMP=25;HUM=50;ID=-1", "TEMP=25;HUM=50;ID=1.2",
                "TEMP=25;HUM=50;ID=1;TEMP=26", "TEMP=25;HUM=50;ID=4294967296", "x".repeat(257)}) {
            assertThrows(IllegalArgumentException.class, () -> Protocol.parse(value), value);
        }
    }

    @Test void keepsTheEightLedMappingFromJavaAndFirmware() {
        assertEquals(1, segments(15));
        assertEquals(1, segments(20));
        assertEquals(4, segments(30));
        assertEquals(8, segments(40));
        assertEquals(8, segments(50));
    }

    @Test void detectsHighTemperatureAndJumpAtExactThresholds() {
        Config config = Config.defaults();
        assertFalse(evaluate(new Sample(25, 50, 1), null, config).alert());
        assertTrue(evaluate(new Sample(32, 50, 2), null, config).highAlert());
        assertTrue(evaluate(new Sample(27, 50, 2), new Sample(25, 50, 1), config).jumpAlert());
        assertFalse(evaluate(new Sample(26.9, 50, 2), new Sample(25, 50, 1), config).jumpAlert());
        assertFalse(evaluate(new Sample(23, 50, 2), new Sample(25, 50, 1), config).alert());
    }

    @Test void validatesConfigurationWithoutDnsLookups() {
        assertDoesNotThrow(() -> Config.defaults().validated());
        for (String ip : new String[]{"", "localhost", "999.1.1.1", "192.168.01.1", "0.0.0.0", "255.255.255.255"})
            assertThrows(IllegalArgumentException.class, () -> new Config("TCP", ip, 32, 2).validated());
        assertThrows(IllegalArgumentException.class, () -> new Config("HTTP", "127.0.0.1", 32, 2).validated());
        assertThrows(IllegalArgumentException.class, () -> new Config("UDP", "127.0.0.1", Double.NaN, 2).validated());
        assertThrows(IllegalArgumentException.class, () -> new Config("UDP", "127.0.0.1", 32, 0).validated());
    }
}
