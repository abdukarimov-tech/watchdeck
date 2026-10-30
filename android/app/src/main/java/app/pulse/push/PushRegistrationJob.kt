package app.pulse.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import app.pulse.crypto.ServersStore
import app.pulse.net.MonitoringApi
import app.pulse.net.RelayAdmin
import kotlinx.coroutines.*

/** System-managed retry when Firebase rotates a device token in the background. */
class PushRegistrationJob:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var task:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val token=params.extras.getString("token")?:return false
        task=scope.launch {
            var failed=false
            for(server in ServersStore(this@PushRegistrationJob).load().filterNot{it.isDemo}) {
                try {
                    val agents=MonitoringApi(server).agentIds()
                    val admin=RelayAdmin(server.relayUrl,server.adminToken,server.relayCertPem)
                    for(agent in agents)admin.registerDevice(server.token,token,agent)
                } catch(e:CancellationException){throw e}
                catch(_:Exception){failed=true}
            }
            jobFinished(params,failed)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {task?.cancel();return true}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object {
        fun schedule(context:Context,token:String) {
            val extras=PersistableBundle().apply{putString("token",token)}
            val job=JobInfo.Builder(8701,ComponentName(context,PushRegistrationJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setExtras(extras).build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
