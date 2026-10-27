package app.pulse

import app.pulse.net.kindLabel
import app.pulse.net.stateLabel
import app.pulse.net.toUi
import org.junit.Assert.assertEquals
import org.junit.Test
import pulse.v1.Container
import pulse.v1.ContainerState
import pulse.v1.EventKind
import pulse.v1.MetricsReport
import pulse.v1.NetStat

/**
 * Маппинг protobuf -> UI-модели работает на JVM (lite runtime), поэтому
 * контракт протокола можно проверять быстрыми unit-тестами без эмулятора.
 */
class ProtoMappingTest {

    @Test
    fun metricsReportMapsToUi() {
        val report = MetricsReport.newBuilder()
            .setCpuUsagePercent(37.5)
            .setCpuCores(8)
            .setMemTotal(16L * 1024 * 1024 * 1024)
            .setMemUsed(4L * 1024 * 1024 * 1024)
            .setUptimeSeconds(900_000)
            .setNet(NetStat.newBuilder().setRxBytesPerSec(1024).setTxBytesPerSec(2048))
            .build()

        val ui = report.toUi()
        assertEquals(37.5, ui.cpuPercent, 0.001)
        assertEquals(8, ui.cpuCores)
        assertEquals(4L * 1024 * 1024 * 1024, ui.memUsedBytes)
        assertEquals(1024L, ui.rxBytesPerSec)
    }

    @Test
    fun containerStatesGetStableLabels() {
        val running = Container.newBuilder().setState(ContainerState.RUNNING).build().toUi()
        assertEquals("RUNNING", running.state)

        val exited = Container.newBuilder().setState(ContainerState.EXITED).build().toUi()
        assertEquals("EXITED", exited.state)

        val unknown = Container.newBuilder().setStateValue(99).build().toUi()
        assertEquals("UNKNOWN", unknown.state)
    }

    @Test
    fun eventKindsGetStableLabels() {
        assertEquals("CONTAINER_DIED", kindLabel(EventKind.CONTAINER_DIED))
        assertEquals("OOM", kindLabel(EventKind.OOM))
    }
}
