package app.pulse.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import app.pulse.MainActivity
import app.pulse.R
import app.pulse.crypto.ServersStore
import app.pulse.data.MetricsUi
import app.pulse.net.ConnState
import org.json.JSONArray
import org.json.JSONObject

/** Widgets receive a non-secret snapshot, never credentials or SSH access. */
object WidgetSnapshots {
    private fun prefs(c:Context)=c.getSharedPreferences("watchdeck_widgets",Context.MODE_PRIVATE)
    private var lastRender=0L
    fun publish(c:Context,servers:List<ServersStore.ServerConfig>,states:Map<String,ConnState>,metrics:Map<String,MetricsUi>,times:Map<String,Long>,agents:Map<String,Map<String,String>>,incidents:Map<String,String>,force:Boolean=false) {
        val rows=JSONArray()
        servers.forEach {s->val m=metrics[s.id];rows.put(JSONObject().put("id",s.id).put("label",s.label).put("state",states[s.id]?.name?:"DISCONNECTED").put("at",times[s.id]?:0)
            .put("cpu",m?.cpuPercent?:0.0).put("memory",if(m!=null && m.memTotalBytes>0)m.memUsedBytes.toDouble()/m.memTotalBytes*100 else 0.0)
            .put("agents",JSONArray(agents[s.id]?.keys?.toList()?:emptyList<String>())).put("incident",incidents[s.id].orEmpty()))}
        prefs(c).edit().putString("snapshot",rows.toString()).apply()
        val now=System.currentTimeMillis()
        if(force||now-lastRender>=60_000){lastRender=now;refresh(c)}
    }
    fun recordIncident(c:Context,agent:String,message:String) {
        val a=JSONArray(prefs(c).getString("snapshot","[]"))
        for(i in 0 until a.length()) {val o=a.getJSONObject(i);val ids=o.optJSONArray("agents")?:continue
            if((0 until ids.length()).any{ids.getString(it)==agent})o.put("incident",message)}
        prefs(c).edit().putString("snapshot",a.toString()).apply();refresh(c)
    }
    fun refresh(c:Context) {
        val manager=AppWidgetManager.getInstance(c)
        manager.getAppWidgetIds(ComponentName(c,ServerWidget::class.java)).forEach{render(c,manager,it)}
    }
    fun select(c:Context,widget:Int,server:String){prefs(c).edit().putString("server-$widget",server).apply();render(c,AppWidgetManager.getInstance(c),widget)}
    fun delete(c:Context,widget:Int){prefs(c).edit().remove("server-$widget").apply()}
    fun render(c:Context,manager:AppWidgetManager,id:Int) {
        val a=runCatching{JSONArray(prefs(c).getString("snapshot","[]"))}.getOrDefault(JSONArray())
        val selected=prefs(c).getString("server-$id",null)
        val row=(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("id")==selected} ?: if(selected==null&&a.length()>0)a.getJSONObject(0)else null
        val views=RemoteViews(c.packageName,R.layout.server_widget)
        views.setTextViewText(R.id.widget_name,row?.optString("label")?:c.getString(R.string.widget_empty))
        val at=row?.optLong("at")?:0;val stale=System.currentTimeMillis()/1000-at>120
        views.setTextViewText(R.id.widget_metrics,if(row==null||at==0L)"—" else String.format(java.util.Locale.US,"CPU %.0f%%  ·  RAM %.0f%%",row.optDouble("cpu"),row.optDouble("memory")))
        val status=if(stale)c.getString(R.string.widget_stale)else c.getString(if(row?.optString("state")=="CONNECTED")R.string.conn_status_online else R.string.conn_status_offline)
        views.setTextViewText(R.id.widget_state,status)
        views.setTextViewText(R.id.widget_incident,row?.optString("incident").orEmpty())
        val time=if(at>0)java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(at*1000))else"—"
        views.setTextViewText(R.id.widget_updated,"${c.getString(R.string.widget_updated)}: $time")
        val intent=Intent(c,MainActivity::class.java).putExtra("server_id",row?.optString("id")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        manager.updateAppWidget(id,views)
    }
}
class ServerWidget:AppWidgetProvider(){
    override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){ids.forEach{WidgetSnapshots.render(c,m,it)}}
    override fun onDeleted(c:Context,ids:IntArray){ids.forEach{WidgetSnapshots.delete(c,it)}}
}
class WidgetConfigureActivity:Activity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widget=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(widget==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return}
        val layout=android.widget.LinearLayout(this).apply{orientation=android.widget.LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        layout.addView(android.widget.TextView(this).apply{text=getString(R.string.widget_select);textSize=22f})
        val servers=ServersStore(this).load()
        if(servers.isEmpty())layout.addView(android.widget.TextView(this).apply{text=getString(R.string.widget_empty)})
        servers.forEach{s->layout.addView(android.widget.Button(this).apply{text=s.label;setOnClickListener{
            WidgetSnapshots.select(this@WidgetConfigureActivity,widget,s.id)
            setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widget));finish()
        }})}
        setContentView(layout)
    }
}
