package com.gama.assistant

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

class GamaVoiceSettingsActivity : Activity() {
    private var tts: TextToSpeech? = null
    private var candidates: List<GamaVoiceCatalog.Candidate> = emptyList()
    private lateinit var list: ListView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashRecorder.markComponent("tts")

        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "Voz do Gama"
            textSize = 24f
        }
        status = TextView(this).apply {
            text = "Carregando vozes instaladas em português..."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
        }
        val preview = Button(this).apply { text = "Ouvir prévia" }
        val automatic = Button(this).apply { text = "Remover voz escolhida" }
        val close = Button(this).apply { text = "Fechar" }

        root.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(preview, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(automatic, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(close, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)

        tts = TextToSpeech(this) { result ->
            if (result != TextToSpeech.SUCCESS) {
                status.text = "O mecanismo de voz não ficou disponível. Tente novamente."
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale("pt", "BR")
            candidates = GamaVoiceCatalog.candidates(engine)
            val labels = candidates.map { it.label }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, labels)
            val saved = GamaVoiceCatalog.savedVoiceName(this)
            val selected = candidates.indexOfFirst { it.voice.name == saved }
            if (selected >= 0) list.setItemChecked(selected, true)
            status.text = if (candidates.isEmpty()) {
                "Nenhuma voz pt-BR/pt-PT foi exposta pelo mecanismo instalado."
            } else {
                "Escolha pela prévia. O Android nem sempre informa gênero nos metadados da voz."
            }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val engine = tts ?: return@setOnItemClickListener
            val candidate = candidates.getOrNull(position) ?: return@setOnItemClickListener
            if (GamaVoiceCatalog.applyVoiceByName(this, engine, candidate.voice.name)) {
                status.text = "Voz salva: ${candidate.voice.name}"
                preview(engine)
            }
        }
        preview.setOnClickListener { tts?.let(::preview) }
        automatic.setOnClickListener {
            GamaVoiceCatalog.saveVoiceName(this, null)
            status.text = "Voz escolhida removida. Escolha e ouça uma voz masculina pela prévia para travá-la novamente."
        }
        close.setOnClickListener { finish() }
    }

    private fun preview(engine: TextToSpeech) {
        GamaVoice.apply(this, engine)
        engine.speak(
            "Senhor, esta é uma prévia da voz do Gama.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "gama-voice-preview"
        )
    }

    override fun onDestroy() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        super.onDestroy()
    }
}
