package com.gama.assistant

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class LocalBrain(private val context: Context) {
    private var llm: LlmInference? = null

    @Synchronized
    fun warmUp() {
        val model = locateModel() ?: return
        if (llm == null) llm = createEngine(model)
    }

    @Synchronized
    fun answer(question: String, conversationHistory: String? = null): String {
        val model = locateModel()
        if (model == null) {
            return "A conversa inteligente ainda não está pronta neste aparelho. Os comandos locais continuam funcionando."
        }

        return try {
            val engine = llm ?: createEngine(model).also { llm = it }
            val history = conversationHistory.orEmpty()
            val prompt = """
                <start_of_turn>user
                ${GamaPersona.prompt(question, history, OwnerIdentity.treatment(context), emptyList(), GamaProfile.focus(context))}
                <end_of_turn>
                <start_of_turn>model
            """.trimIndent()

            val answer = engine.generateResponse(prompt)
                .replace("<end_of_turn>", "")
                .replace("<start_of_turn>", "")
                .trim()

            if (answer.isBlank()) throw IllegalStateException("empty response")
            AiSetup.markWorking(context, model)
            answer.take(900)
        } catch (t: Exception) {
            Log.e("GamaBrain", "Local inference failed", t)
            AiSetup.markFailure(context, t.javaClass.simpleName.take(80))
            close()
            "Não consegui concluir essa resposta agora. Os comandos do celular continuam disponíveis."
        }
    }

    private fun createEngine(model: File): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTokens(512)
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    private fun locateModel(): File? {
        val internal = File(context.filesDir, "gemma.task")
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "gemma.task") }
        return listOfNotNull(internal, external)
            .filter { it.isFile && it.length() > 500_000_000L }
            .maxByOrNull { it.lastModified() }
    }

    @Synchronized
    fun close() {
        try { llm?.close() } catch (_: Exception) {}
        llm = null
    }
}
