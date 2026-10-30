package app.pulse.data

data class DiskUi(val mount: String, val totalBytes: Long, val usedBytes: Long)

data class MetricsUi(
    val cpuPercent: Double,
    val cpuCores: Int,
    val memTotalBytes: Long,
    val memUsedBytes: Long,
    val disks: List<DiskUi>,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
    val uptimeSeconds: Long,
)

data class ContainerUi(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val cpuPercent: Double = 0.0,
    val memUsedBytes: Long = 0,
    val memLimitBytes: Long = 0,
    val health: String = "none",
    val composeProject: String = "",
    val composeService: String = "",
)

data class LogLineUi(val tsUnix: Long, val isStderr: Boolean, val text: String)

private val logErrorPattern = Regex("\\b(ERROR|FATAL|PANIC)\\b", RegexOption.IGNORE_CASE)
private val logWarnPattern = Regex("\\bWARN(ING)?\\b", RegexOption.IGNORE_CASE)
fun LogLineUi.level(): String = when {
    logErrorPattern.containsMatchIn(text) -> "ERROR"
    logWarnPattern.containsMatchIn(text) -> "WARN"
    isStderr -> "ERROR"
    else -> "INFO"
}

data class AlertUi(
    val kind: String,
    val containerName: String,
    val message: String,
    val atUnix: Long,
    val agentId: String = "",
)

fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    // Locale.US — чтобы разделитель не зависел от локали устройства
    return if (unit == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    return when {
        days > 0 -> "$days д $hours ч"
        hours > 0 -> "$hours ч"
        else -> "${seconds / 60} мин"
    }
}
