package dev.astra.cli;

import dev.astra.Main;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptionsTest {
    @Test void rejectsUnknownMissingAndRepeatedValues() {
        assertThrows(IllegalArgumentException.class, () -> new Options(new String[]{"--typo", "1"}, 0, "workers"));
        assertThrows(IllegalArgumentException.class, () -> new Options(new String[]{"--workers"}, 0, "workers"));
        assertThrows(IllegalArgumentException.class, () -> new Options(new String[]{"--workers","1","--workers","2"}, 0, "workers"));
        assertThrows(IllegalArgumentException.class, () -> new Options(new String[]{"--crc","yes"}, 0, "crc").bool("crc", false));
    }
    @Test void baselineRejectsMisleadingPoolTuningOptions() {
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"server","--design","baseline","--workers","4"}));
    }
}
