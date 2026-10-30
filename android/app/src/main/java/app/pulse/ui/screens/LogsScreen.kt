package app.pulse.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.R
import app.pulse.data.ContainerUi
import app.pulse.data.LogLineUi
import app.pulse.data.level
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseWarn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogsScreen(lines:List<LogLineUi>,containers:List<ContainerUi> = emptyList(),selectedId:String?=null,onSelect:(String)->Unit={}) {
    var filter by remember {mutableStateOf("ALL")}
    var search by remember {mutableStateOf("")}
    var paused by remember(selectedId){mutableStateOf(false)}
    var frozen by remember(selectedId){mutableStateOf(emptyList<LogLineUi>())}
    var choose by remember {mutableStateOf(false)}
    var exportText by remember {mutableStateOf("")}
    var exportError by remember {mutableStateOf<String?>(null)}
    val context=LocalContext.current;val clipboard=LocalClipboardManager.current
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null)try {context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(exportText)} ?: error("Cannot open export")}
        catch(e:Exception){exportError=e.message}
    }
    val source=if(paused)frozen else lines
    val visible=source.filter {line->
        (filter=="ALL" || line.level()==filter) && line.text.contains(search,true)
    }
    val text=visible.joinToString("\n"){"${formatTime(it.tsUnix)} ${it.text}"}
    val state=rememberLazyListState()
    LaunchedEffect(lines.lastOrNull(),paused,search,filter){if(!paused && visible.isNotEmpty())state.scrollToItem(visible.lastIndex)}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)) {
        Box {
            OutlinedButton(onClick={choose=true},enabled=containers.isNotEmpty()) {Text(containers.firstOrNull{it.id==selectedId}?.name?:stringResource(R.string.logs_choose))}
            DropdownMenu(expanded=choose,onDismissRequest={choose=false}){containers.forEach {c->DropdownMenuItem(text={Text(c.name)},onClick={onSelect(c.id);choose=false})}}
        }
        OutlinedTextField(value=search,onValueChange={search=it},label={Text(stringResource(R.string.logs_search))},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for(level in listOf("ALL","ERROR","WARN"))FilterChip(selected=filter==level,onClick={filter=level},label={Text(if(level=="ALL")stringResource(R.string.logs_filter_all)else level)})
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={if(!paused)frozen=lines.toList();paused=!paused}){Text(stringResource(if(paused)R.string.logs_resume else R.string.logs_pause))}
            TextButton(onClick={clipboard.setText(AnnotatedString(text))},enabled=visible.isNotEmpty()){Text(stringResource(R.string.logs_copy))}
            TextButton(onClick={exportText=text;exporter.launch("watchdeck-logs-${System.currentTimeMillis()}.txt")},enabled=visible.isNotEmpty()){Text(stringResource(R.string.logs_export))}
        }
        exportError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Text(stringResource(R.string.logs_buffer,visible.size),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color=Color(0xFF0D1117),shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().weight(1f).padding(vertical=8.dp)) {
            if(visible.isEmpty())Text(stringResource(R.string.logs_empty),color=Color(0xFF9AA4B5),modifier=Modifier.padding(16.dp))
            else SelectionContainer {LazyColumn(state=state,contentPadding=PaddingValues(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                itemsIndexed(visible){_,line->
                    val color=when(line.level()){"ERROR"->PulseError;"WARN"->PulseWarn;else->Color(0xFFC9D4E5)}
                    Row {Text(formatTime(line.tsUnix),modifier=Modifier.width(64.dp),color=Color(0xFF9AA4B5),fontFamily=FontFamily.Monospace,fontSize=11.sp);Text(line.text,color=color,fontFamily=FontFamily.Monospace,fontSize=12.sp)}
                }
            }}
        }
    }
}
private fun formatTime(ts:Long):String=if(ts>0)DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(ts))else"—"
