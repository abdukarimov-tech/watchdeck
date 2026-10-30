package app.pulse

import app.pulse.data.LogLineUi
import app.pulse.data.level
import app.pulse.net.toUi
import app.pulse.net.kindLabel
import org.junit.Assert.*
import org.junit.Test
import pulse.v1.Container
import pulse.v1.EventKind
import pulse.v1.LogsRequest
import pulse.v1.Msg

class MonitoringMappingTest {
    @Test fun healthAndComposeMetadataSurviveMapping() {
        val model = Container.newBuilder().setId("web").setHealth("unhealthy")
            .setComposeProject("prod").setComposeService("api").build().toUi()
        assertEquals("unhealthy", model.health)
        assertEquals("prod", model.composeProject)
        assertEquals("api", model.composeService)
        assertEquals("none", Container.getDefaultInstance().toUi().health)
    }
    @Test fun stderrWarningsAreNotClassifiedAsErrors() {
        assertEquals("WARN", LogLineUi(0,true,"WARN slow query").level())
        assertEquals("ERROR", LogLineUi(0,false,"[fatal] process stopped").level())
        assertEquals("INFO", LogLineUi(0,false,"error_count=0").level())
    }
    @Test fun cancellationAndNewEventKindsAreEncoded() {
        val msg = Msg.newBuilder().setTarget("a").setLogsRequest(LogsRequest.newBuilder()
            .setContainerId("c").setStreamId("unique").setCancel(true)).build()
        assertTrue(Msg.parseFrom(msg.toByteArray()).logsRequest.cancel)
        assertEquals("RESTART_LOOP",kindLabel(EventKind.RESTART_LOOP))
        assertEquals("RESOURCE_HIGH",kindLabel(EventKind.RESOURCE_HIGH))
    }
}
