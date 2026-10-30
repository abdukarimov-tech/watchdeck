package app.pulse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pulse.R
import app.pulse.crypto.ServersStore
import app.pulse.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class LocatedIncident(val server: ServersStore.ServerConfig, val incident: Incident)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentCenter(servers: List<ServersStore.ServerConfig>, onBack: () -> Unit,
    onLogs: (String, String, String) -> Unit) {
    BackHandler(onBack = onBack)
    var rows by remember { mutableStateOf(emptyList<LocatedIncident>()) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var loaded by remember { mutableStateOf(false) }
    var activeOnly by remember { mutableStateOf(true) }
    val apis = remember(servers) { servers.distinctBy { if (it.isDemo) it.id else it.relayUrl.trimEnd('/') }.map { it to MonitoringApi(it) } }
    LaunchedEffect(apis) {
        while (isActive) {
            val next = mutableListOf<LocatedIncident>(); val failures = mutableListOf<String>()
            for ((server,api) in apis) {
                try { next += api.incidents().map { LocatedIncident(server,it) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures += "${server.label}: ${e.message}" }
            }
            rows=next.sortedWith(compareBy<LocatedIncident> { it.incident.resolvedAt != null }.thenByDescending { it.incident.lastAt })
            errors=failures;loaded=true;delay(15_000)
        }
    }
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.incidents_title))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription=stringResource(R.string.nav_back))}})}) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=activeOnly,onClick={activeOnly=true},label={Text(stringResource(R.string.incidents_active))})
                FilterChip(selected=!activeOnly,onClick={activeOnly=false},label={Text(stringResource(R.string.incidents_all))})
            }}
            items(errors) { Text(it,color=MaterialTheme.colorScheme.error) }
            val visible=rows.filter { !activeOnly || it.incident.resolvedAt==null }
            if (loaded && visible.isEmpty() && errors.isEmpty()) item { Text(stringResource(R.string.incidents_empty)) }
            if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(visible,key={"${it.server.id}:${it.incident.id}"}) { located ->
                val i=located.incident
                Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${located.server.label} · ${i.agentId}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(incidentKind(i.kind),style=MaterialTheme.typography.titleMedium)
                    if (i.containerName.isNotBlank()) Text(i.containerName)
                    Text(i.message,style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(if(i.resolvedAt==null)R.string.incidents_active else R.string.incidents_resolved),color=if(i.resolvedAt==null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.incident_opened,timeText(i.openedAt)),style=MaterialTheme.typography.bodySmall)
                    i.resolvedAt?.let { Text(stringResource(R.string.incident_closed,timeText(it)),style=MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.incident_occurrences,i.occurrences),style=MaterialTheme.typography.labelSmall)
                    if(i.containerId.isNotEmpty()) TextButton(onClick={onLogs(located.server.id,i.agentId,i.containerId)}) {Text(stringResource(R.string.incident_logs))}
                }}
            }
        }
    }
}
@Composable
private fun incidentKind(kind:String):String = stringResource(when(kind) {
    "CPU_HIGH"->R.string.rule_cpu;"MEMORY_HIGH"->R.string.rule_memory;"DISK_HIGH"->R.string.rule_disk
    "SERVER_OFFLINE"->R.string.incident_server_offline;"OOM"->R.string.incident_oom
    "RESTART_LOOP"->R.string.incident_restart_loop;"CONTAINER_UNHEALTHY"->R.string.incident_unhealthy
    else->R.string.incident_container_exit
})
private fun timeText(at:Long):String=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(at*1000))

@Composable
fun HistoryScreen(server:ServersStore.ServerConfig,agent:String?) {
    val api=remember(server){MonitoringApi(server)}
    var hours by remember { mutableIntStateOf(24) }
    var points by remember(server.id,agent) { mutableStateOf(emptyList<MetricPoint>()) }
    var error by remember(server.id,agent,hours){mutableStateOf<String?>(null)}
    var loading by remember(server.id,agent,hours){mutableStateOf(true)}
    LaunchedEffect(api,agent,hours) {
        points=emptyList()
        if(agent==null){loading=false;return@LaunchedEffect}
        while(isActive) {
            try {points=api.history(agent,hours);error=null}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message}
            loading=false;delay(30_000)
        }
    }
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {for(h in listOf(1,24,168)) FilterChip(selected=hours==h,onClick={hours=h},label={Text(stringResource(when(h){1->R.string.range_hour;24->R.string.range_day;else->R.string.range_week}))})}}
        if(server.isDemo) item {Text(stringResource(R.string.servers_demo_caption),style=MaterialTheme.typography.labelSmall)}
        error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        if(loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(!loading && points.isEmpty() && error==null) item {Text(stringResource(R.string.history_empty))}
        if(points.isNotEmpty()) {
            item {Text(stringResource(R.string.history_percent),style=MaterialTheme.typography.titleMedium)}
            item {HistoryPlot(points, false)}
            item {Text(stringResource(R.string.history_network),style=MaterialTheme.typography.titleMedium)}
            item {HistoryPlot(points, true)}
            item {Text(stringResource(R.string.history_retention),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}
@Composable
private fun HistoryPlot(points:List<MetricPoint>,network:Boolean) {
    val colors=listOf(MaterialTheme.colorScheme.primary,app.pulse.ui.theme.PulseSuccess,app.pulse.ui.theme.PulseWarn)
    val labels=if(network)listOf(stringResource(R.string.history_rx),stringResource(R.string.history_tx)) else listOf("CPU","RAM",stringResource(R.string.widget_disk))
    val values=if(network)listOf(points.map{it.rx},points.map{it.tx}) else listOf(points.map{it.cpu},points.map{it.memory},points.map{it.disk})
    val max=if(network)values.flatten().maxOrNull()?.coerceAtLeast(1.0)?:1.0 else 100.0
    val grid=MaterialTheme.colorScheme.outlineVariant
    val description=stringResource(if(network)R.string.history_network else R.string.history_percent)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){labels.forEachIndexed {i,label->Text(label,color=colors[i],style=MaterialTheme.typography.labelSmall)}}
        Text(if(network)app.pulse.data.formatBytes(max.toLong())+"/s" else "100%",style=MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics{contentDescription=description}) {
            for(f in listOf(0f,0.5f,1f))drawLine(grid,Offset(0f,f*size.height),Offset(size.width,f*size.height),1.dp.toPx())
            val first=points.first().at;val span=(points.last().at-first).coerceAtLeast(1)
            values.forEachIndexed { series, samples ->
                val path=Path()
                samples.forEachIndexed {i,v->
                    val x=((points[i].at-first).toDouble()/span*size.width).toFloat()
                    val y=(size.height*(1-v/max)).toFloat().coerceIn(0f,size.height)
                    if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
                    drawCircle(colors[series],2.dp.toPx(),Offset(x,y))
                }
                drawPath(path,colors[series],style=Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(timeText(points.first().at),style=MaterialTheme.typography.labelSmall);Text(timeText(points.last().at),style=MaterialTheme.typography.labelSmall)}
    }
}

@Composable
fun AlertRulesScreen(server:ServersStore.ServerConfig,agent:String?) {
    val api=remember(server){MonitoringApi(server)};val scope=rememberCoroutineScope()
    var policy by remember(server.id,agent){mutableStateOf<AlertPolicy?>(null)}
    var error by remember(server.id,agent){mutableStateOf<String?>(null)}
    var saving by remember {mutableStateOf(false)}
    var saved by remember {mutableStateOf(false)}
    LaunchedEffect(api,agent) {
        if(agent!=null) try {policy=api.policy(agent)}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
    }
    val p=policy
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item{Text(stringResource(R.string.rules_description),style=MaterialTheme.typography.bodySmall)}
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(p==null && error==null)item{Text(stringResource(R.string.dashboard_loading))}
        if(p!=null) {
            item{Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.rules_enabled),Modifier.weight(1f));Switch(checked=p.enabled,onCheckedChange={policy=p.copy(enabled=it);saved=false})}}
            item{RuleSlider(stringResource(R.string.rule_cpu),p.cpu.toFloat(),1f..100f){policy=p.copy(cpu=it.toDouble());saved=false}}
            item{RuleSlider(stringResource(R.string.rule_memory),p.memory.toFloat(),1f..100f){policy=p.copy(memory=it.toDouble());saved=false}}
            item{RuleSlider(stringResource(R.string.rule_disk),p.disk.toFloat(),1f..100f){policy=p.copy(disk=it.toDouble());saved=false}}
            item{RuleSlider(stringResource(R.string.rule_duration),p.sustain.toFloat(),0f..3600f,"s"){policy=p.copy(sustain=it.toInt());saved=false}}
            item{RuleSlider(stringResource(R.string.rule_restarts),p.restarts.toFloat(),2f..10f,""){policy=p.copy(restarts=it.toInt());saved=false}}
            item{Text(stringResource(R.string.rules_dedupe),style=MaterialTheme.typography.bodySmall)}
            item{
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.maintenance_title),style=MaterialTheme.typography.titleMedium)
                    if(p.maintenanceUntil>System.currentTimeMillis()/1000)Text(stringResource(R.string.maintenance_until,timeText(p.maintenanceUntil)))
                    Row {TextButton(onClick={policy=p.copy(maintenanceUntil=System.currentTimeMillis()/1000+3600);saved=false}){Text(stringResource(R.string.maintenance_hour))};TextButton(onClick={policy=p.copy(maintenanceUntil=0);saved=false}){Text(stringResource(R.string.maintenance_off))}}
                }
            }
            item{Button(enabled=!saving && agent!=null,onClick={scope.launch {saving=true;try{api.savePolicy(agent!!,p);error=null;saved=true}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}finally{saving=false}}}){Text(stringResource(R.string.rules_save))}}
            if(saved)item{Text(stringResource(R.string.rules_saved))}
        }
    }
}
@Composable
private fun RuleSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,unit:String="%",onChange:(Float)->Unit) {
    Column {Text("$label: ${value.toInt()}$unit");Slider(value=value.coerceIn(range),onValueChange=onChange,valueRange=range,steps=when(unit){"%"->98;"s"->119;else->7},modifier=Modifier.semantics{contentDescription=label})}
}
