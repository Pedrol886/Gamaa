package com.gama.assistant
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
class LockScreenActivity:Activity(){
 companion object {const val ACTION_SPEAKING="com.gama.assistant.SPEAKING";const val ACTION_LISTENING="com.gama.assistant.LISTENING"}
 private var requested=false
 private lateinit var status:TextView
 override fun onCreate(saved:Bundle?){
  super.onCreate(saved)
  if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true)}else{
   @Suppress("DEPRECATION")
   window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
  }
  window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(32,40,32,40);setBackgroundColor(Color.rgb(9,16,25))}
  status=TextView(this).apply{text="Gama\nDesbloqueio do Android";textSize=24f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)}
  root.addView(status)
  root.addView(Button(this).apply{text="Desbloquear";setOnClickListener{requested=false;unlock()}})
  root.addView(Button(this).apply{text="Cancelar";setOnClickListener{AssistantRuntime.service?.cancelRequest();finish()}})
  setContentView(root);window.decorView.post{unlock()}
 }
 private fun unlock(){
  if(requested||isFinishing)return
  requested=true
  val keyguard=getSystemService(KeyguardManager::class.java)
  if(!keyguard.isKeyguardLocked){complete();return}
  keyguard.requestDismissKeyguard(this,object:KeyguardManager.KeyguardDismissCallback(){
   override fun onDismissSucceeded(){if(!isFinishing)complete()}
   override fun onDismissCancelled(){requested=false;status.text="Desbloqueio cancelado";AssistantRuntime.service?.cancelRequest()}
   override fun onDismissError(){requested=false;status.text="Use o PIN ou a biometria na tela de bloqueio do Android."}
  })
 }
 private fun complete(){if(getSystemService(KeyguardManager::class.java).isDeviceLocked)return;AssistantRuntime.service?.resumeAfterUnlock();finish()}
 override fun onNewIntent(intent:Intent?){super.onNewIntent(intent);setIntent(intent);requested=false;unlock()}
}
