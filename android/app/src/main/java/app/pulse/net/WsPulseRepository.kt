package app.pulse.net

import app.pulse.crypto.ServersStore
import app.pulse.data.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Релизная реализация репозитория: живой WS-канал к релею.
 * Владеет своим scope и закрывает его в [close].
 */
class WsPulseRepository(config: ServersStore.ServerConfig) : PulseRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conn = PulseConnection(scope, config.relayUrl, config.token, config.relayCertPem)

    override val connectionState = conn.connectionState
    override val agents = conn.agents
    override val versions = conn.versions
    override val metricsByAgent = conn.metricsByAgent
    override val containersByAgent = conn.containersByAgent
    override val logLines = conn.logLines
    override val events = conn.events

    override suspend fun action(agentId: String, containerId: String, kind: String): Result<Unit> =
        conn.action(agentId, containerId, kind)

    override fun requestLogs(agentId: String, containerId: String) =
        conn.requestLogs(agentId, containerId)

    override fun close() {
        conn.close()
        scope.cancel()
    }
}
