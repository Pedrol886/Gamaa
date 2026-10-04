package com.gama.assistant
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object AssistantRuntime {
 data class Line(val user:Boolean,val text:String)
 private val history=ArrayList<Line>()
 private val observers=CopyOnWriteArraySet<()->Unit>()
 private val main=Handler(Looper.getMainLooper())
 @Volatile var service:GamaService?=null
 @Volatile var state="Voz pausada";private set
 @Volatile var partial="";private set
 @Synchronized fun lines():List<Line> = history.toList()
 @Synchronized fun add(user:Boolean,text:String){history.add(Line(user,text.take(1600)));while(history.size>40)history.removeAt(0);changed()}
 @Synchronized fun clear(){history.clear();partial="";changed()}
 fun state(value:String){state=value;changed()}
 fun partial(value:String){partial=value;changed()}
 fun observe(callback:()->Unit){observers.add(callback);main.post(callback)}
 fun remove(callback:()->Unit){observers.remove(callback)}
 private fun changed(){main.post{observers.forEach{it()}}}
}
class GamaApplication:Application(),Application.ActivityLifecycleCallbacks {
 companion object{@Volatile var foreground=false;private set}
 private var count=0
 override fun onCreate(){
  super.onCreate()
  val previous=Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler{thread,error->
   try{getSharedPreferences("gama_crash_log",MODE_PRIVATE).edit().putLong("at",System.currentTimeMillis()).putString("last",error.stackTraceToString().take(12000)).commit()}catch(_:Exception){}
   if(previous!=null)previous.uncaughtException(thread,error) else Unit
  }
  registerActivityLifecycleCallbacks(this)
 
        // FIX72: outer privacy-safe fail-soft crash supervisor
        CrashRecorder.install(this)
}
 override fun onActivityResumed(a:Activity){count++;foreground=true}
 override fun onActivityPaused(a:Activity){count=(count-1).coerceAtLeast(0);foreground=count>0}
 override fun onActivityCreated(a:Activity,b:Bundle?)=Unit
 override fun onActivityStarted(a:Activity)=Unit
 override fun onActivityStopped(a:Activity)=Unit
 override fun onActivitySaveInstanceState(a:Activity,b:Bundle)=Unit
 override fun onActivityDestroyed(a:Activity)=Unit
}
/** Not a biometric decision: Android remains the authority for protected commands. */
object AccessPolicy {
 fun requiresUnlock(locked:Boolean,safeCommand:Boolean):Boolean=locked && !safeCommand
}
class PendingCommand {
 private var command:String?=null
 private var until=0L
 @Synchronized fun set(value:String,now:Long){command=value;until=now+90000L}
 @Synchronized fun take(locked:Boolean,now:Long):String?{if(locked)return null;val c=if(now<=until)command else null;clear();return c}
 @Synchronized fun clear(){command=null;until=0}
}
