package com.gama.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import java.util.Locale

data class WhatsAppOpenResult(val reply: String, val opened: Boolean)

object WhatsAppMessaging {
    fun looksLikeSendCommand(raw: String): Boolean = WhatsAppCommandParser.looksLikeSendCommand(raw)
    fun parse(raw: String): WhatsAppSendRequest? = WhatsAppCommandParser.parse(raw)
    fun cleanTarget(raw: String): String = WhatsAppCommandParser.cleanTarget(raw)
    fun followUpBody(raw: String): String = WhatsAppCommandParser.followUpBody(raw)

    fun hasContactsPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun openPreparedChat(context: Context, target: String, message: String): WhatsAppOpenResult {
        val pkg = installedWhatsApp(context) ?: return WhatsAppOpenResult("Não encontrei o WhatsApp instalado neste celular.", false)
        if (!hasContactsPermission(context)) {
            return WhatsAppOpenResult("Preciso de acesso aos contatos para localizar $target no WhatsApp.", false)
        }
        val match = findContact(context, target)
        if (match == null) {
            return try {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(share)
                WhatsAppOpenResult("Não encontrei um contato único chamado $target. Abri o WhatsApp com a mensagem pronta para você escolher a conversa.", true)
            } catch (_: Exception) {
                WhatsAppOpenResult("Não encontrei um contato único chamado $target.", false)
            }
        }
        val digits = toInternationalDigits(match.second)
            ?: return WhatsAppOpenResult("Encontrei ${match.first}, mas o número salvo não pôde ser convertido para o WhatsApp.", false)
        return try {
            val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            WhatsAppOpenResult("Abri a conversa de ${match.first} com a mensagem pronta. Para uma conversa nova, o WhatsApp exige tocar em Enviar.", true)
        } catch (_: Exception) {
            WhatsAppOpenResult("Encontrei ${match.first}, mas não consegui abrir a conversa no WhatsApp.", false)
        }
    }

    private fun installedWhatsApp(context: Context): String? = listOf("com.whatsapp", "com.whatsapp.w4b")
        .firstOrNull { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }

    private fun findContact(context: Context, target: String): Pair<String, String>? {
        val wanted = cleanTarget(target)
        if (wanted.isBlank()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && candidates.size < 1200) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty().trim() else ""
                    val number = if (numCol >= 0) cursor.getString(numCol).orEmpty().trim() else ""
                    if (name.isNotBlank() && number.isNotBlank()) candidates += name to number
                }
            }
        } catch (_: Exception) { return null }
        val exact = candidates.filter { VoicePolicy.normalize(it.first) == wanted }.distinctBy { it.first + it.second }
        if (exact.size == 1) return exact.first()
        val partial = candidates.filter {
            val n = VoicePolicy.normalize(it.first)
            n.contains(wanted) || wanted.contains(n)
        }.distinctBy { VoicePolicy.normalize(it.first) }
        return if (partial.size == 1) partial.first() else null
    }

    private fun toInternationalDigits(raw: String): String? {
        val country = Locale.getDefault().country.ifBlank { "BR" }
        val e164 = try { PhoneNumberUtils.formatNumberToE164(raw, country) } catch (_: Exception) { null }
        val digits = (e164 ?: PhoneNumberUtils.normalizeNumber(raw)).filter { it.isDigit() }
        return digits.takeIf { it.length in 8..15 }
    }
}
