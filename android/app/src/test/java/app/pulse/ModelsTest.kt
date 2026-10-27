package app.pulse

import app.pulse.data.formatBytes
import app.pulse.data.formatUptime
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {

    @Test
    fun bytesFormatAcrossUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.0 MiB", formatBytes(2L * 1024 * 1024))
        assertEquals("8.0 GiB", formatBytes(8L * 1024 * 1024 * 1024))
    }

    @Test
    fun uptimeFormatsDaysAndHours() {
        assertEquals("2 д 3 ч", formatUptime(2 * 86_400L + 3 * 3_600L))
        assertEquals("5 ч", formatUptime(5 * 3_600L))
        assertEquals("42 мин", formatUptime(42 * 60L))
    }
}
