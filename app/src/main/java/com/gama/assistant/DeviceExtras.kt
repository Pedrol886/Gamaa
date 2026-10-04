package com.gama.assistant
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONArray
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
object DeviceExtras {
 private val help=setOf("ajuda","o que voce faz","o que voce pode fazer","quem e voce")
 private val dates=setOf("que dia e hoje","qual a data","data")
 fun safe(c:String)=c in help || c in dates
 fun known(c:String)=safe(c)||c.startsWith("anote ")||c in setOf("minhas notas","leia minhas notas")||c.startsWith("timer ")||c.startsWith("temporizador ")||c.startsWith("alarme ")||c.startsWith("defina um alarme ")||c.startsWith("pesquise ")
 private val numbers=mapOf("um" to 1,"uma" to 1,"dois" to 2,"duas" to 2,"tres" to 3,"quatro" to 4,"cinco" to 5,"seis" to 6,"sete" to 7,"oito" to 8,"nove" to 9,"dez" to 10,"onze" to 11,"doze" to 12,"treze" to 13,"quatorze" to 14,"quinze" to 15,"dezesseis" to 16,"dezessete" to 17,"dezoito" to 18,"dezenove" to 19,"vinte" to 20,"trinta" to 30,"quarenta" to 40,"cinquenta" to 50)
 fun number(s:String):Int? {s.toIntOrNull()?.let{return it};numbers[s]?.let{return it};val bits=s.split(" e ");if(bits.size==2){val a=numbers[bits[0]];val b=numbers[bits[1]];if(a!=null&&a>=20&&b!=null&&b in 1..9)return a+b};return null}
 fun timer(c:String):Int? {val m=Regex("^(?:timer|temporizador)(?: de| por)? (.+) (segundos?|minutos?|horas?)$").matchEntire(c)?:return null;val n=number(m.groupValues[1])?.toLong()?:return null;val multiplier=if(m.groupValues[2].startsWith("hora"))3600 else if(m.groupValues[2].startsWith("min"))60 else 1;val value=n*multiplier;return value.takeIf{it in 1..86400}?.toInt()}
 fun alarm(c:String):Pair<Int,Int>? {val text=c.replace(Regex("^(?:defina um )?alarme(?: para| as)? "),"").replace(" e meia",":30").replace(Regex("^(\\d{1,2}) (\\d{1,2})$"),"$1:$2");val parts=text.split(':');val h=number(parts[0])?:return null;val m=if(parts.size==1)0 else number(parts[1])?:return null;return (h to m).takeIf{parts.size<=2&&h in 0..23&&m in 0..59}}
 fun answer(service:GamaService,c:String,raw:String=c):String? {
  if(!known(c))return null
  if(c in help)return "Sou o Gama. Posso conversar, abrir aplicativos, controlar partes do sistema, navegar pela interface com a acessibilidade ativada, ler notificações, responder mensagens compatíveis, usar calendário, alarmes, mídia, notas, pesquisas e executar comandos em sequência."
  if(c in dates)return "Hoje é ${SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date())}."
  val prefs=service.getSharedPreferences("gama_notes",0)
  if(c.startsWith("anote ")){val old=JSONArray(prefs.getString("items","[]"));val arr=JSONArray().put(raw.replaceFirst(Regex("(?i)^anote\\s+"),"").take(1000));for(i in 0 until minOf(29,old.length()))arr.put(old.getString(i));prefs.edit().putString("items",arr.toString()).apply();return "Anotado."}
  if(c in setOf("minhas notas","leia minhas notas")){val arr=JSONArray(prefs.getString("items","[]"));return if(arr.length()==0)"Você ainda não tem notas. Diga Gama, anote, seguido da sua nota." else (0 until minOf(5,arr.length())).joinToString(". "){"${it+1}: ${arr.getString(it).take(180)}"}}
  if(c.startsWith("timer ")||c.startsWith("temporizador ")){val seconds=timer(c)?:return "Diga, por exemplo: timer de cinco minutos.";return service.launchSafely(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,seconds).putExtra(AlarmClock.EXTRA_SKIP_UI,false).putExtra(AlarmClock.EXTRA_MESSAGE,"Gama"))}
  if(c.contains("alarme ")){val time=alarm(c)?:return "Diga, por exemplo: alarme para sete e meia.";return service.launchSafely(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,time.first).putExtra(AlarmClock.EXTRA_MINUTES,time.second).putExtra(AlarmClock.EXTRA_SKIP_UI,false).putExtra(AlarmClock.EXTRA_MESSAGE,"Gama"))}
  if(c.startsWith("pesquise "))return service.launchSafely(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+URLEncoder.encode(c.removePrefix("pesquise "),"UTF-8"))))
  return null
 }
}
