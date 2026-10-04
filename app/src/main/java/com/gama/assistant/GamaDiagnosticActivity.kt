package com.gama.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Private, human-readable view of the privacy-safe CrashRecorder entry.
 * No audio, commands, message bodies, contacts, notification contents,
 * calendar text or conversation content are stored here.
 */
class GamaDiagnosticActivity : Activity() {
    private lateinit var detailsView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 8, 10)
        window.navigationBarColor = Color.rgb(8, 8, 10)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
            setBackgroundColor(Color.rgb(8, 8, 10))
        }

        page.addView(TextView(this).apply {
            text = "Diagnóstico do Gama"
            setTextColor(Color.WHITE)
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        })

        page.addView(TextView(this).apply {
            text = "Último erro registrado no aparelho. O relatório abaixo não inclui áudio, comandos, mensagens, contatos, notificações privadas, agenda nem conversa."
            setTextColor(Color.rgb(180, 180, 188))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(18))
        })

        statusView = TextView(this).apply {
            setTextColor(Color.rgb(255, 176, 66))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        page.addView(statusView)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(24, 24, 29))
        }

        detailsView = TextView(this).apply {
            setTextColor(Color.rgb(238, 238, 242))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        card.addView(detailsView)
        page.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        page.addView(actionButton("Copiar diagnóstico") {
            val text = detailsView.text?.toString().orEmpty()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico do Gama", text))
            Toast.makeText(this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show()
        })

        page.addView(actionButton("Limpar registro") {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            reload()
            Toast.makeText(this, "Registro limpo.", Toast.LENGTH_SHORT).show()
        })

        page.addView(actionButton("Fechar") { finish() })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        setContentView(scroll)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST, null).orEmpty().trim()
        val at = prefs.getLong(KEY_AT, 0L)
        val component = prefs.getString(KEY_COMPONENT, "desconhecido").orEmpty()
        val process = prefs.getString(KEY_PROCESS, packageName).orEmpty()
        val thread = prefs.getString(KEY_THREAD, "desconhecida").orEmpty()

        if (last.isBlank()) {
            statusView.text = "Nenhum erro registrado"
            detailsView.text = "O Gama ainda não tem um crash salvo neste registro."
            return
        }

        statusView.text = "Último erro registrado"
        val whenText = if (at > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(at))
        } else {
            "horário indisponível"
        }

        detailsView.text = buildString {
            append("Data: ").append(whenText).append('\n')
            append("Componente: ").append(component).append('\n')
            append("Processo: ").append(process).append('\n')
            append("Thread: ").append(thread).append("\n\n")
            append("Detalhes técnicos:\n").append(last)
        }
    }

    private fun actionButton(label: String, action: (View) -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.CENTER
            setOnClickListener(action)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50),
            )
            params.topMargin = dp(12)
            layoutParams = params
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "gama_crash_log"
        private const val KEY_LAST = "last"
        private const val KEY_AT = "at"
        private const val KEY_COMPONENT = "component"
        private const val KEY_PROCESS = "process"
        private const val KEY_THREAD = "thread"
    }
}
