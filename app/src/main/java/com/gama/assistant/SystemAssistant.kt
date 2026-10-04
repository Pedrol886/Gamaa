package com.gama.assistant
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
class SystemAssistant:VoiceInteractionService(){
 companion object{@Volatile var current:SystemAssistant?=null;private set}
 override fun onReady(){super.onReady();current=this}
 override fun onShutdown(){if(current===this)current=null;super.onShutdown()}
 fun present(intent:Intent){showSession(Bundle().apply{putParcelable("target",intent)},0)}
}
class SystemAssistantSession:VoiceInteractionSessionService(){
 override fun onNewSession(args:Bundle?):VoiceInteractionSession=object:VoiceInteractionSession(this){
  override fun onShow(args:Bundle?,flags:Int){
   super.onShow(args,flags)
   @Suppress("DEPRECATION")
   val target=args?.getParcelable<Intent>("target")?:Intent(this@SystemAssistantSession,MainActivity::class.java).setAction(Intent.ACTION_ASSIST)
   try{startAssistantActivity(target)}catch(_:Exception){AssistantRuntime.state("Abra o Gama pela notificação para continuar")}
   finish()
  }
 }
}
