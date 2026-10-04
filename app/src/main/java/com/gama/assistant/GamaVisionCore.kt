package com.gama.assistant

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object GamaVisionCore {
    private const val OCR_TIMEOUT_SECONDS = 7L
    private const val MAX_TEXT = 9000

    fun recognizes(raw: String): Boolean {
        val c = normalize(raw)
        val mentionsScreen = listOf(
            "tela", "display", "visor", "imagem", "pdf", "documento"
        ).any { it in c } || c in setOf(
            "o que estou vendo", "o que eu estou vendo", "leia isso", "le isso",
            "o que aparece aqui", "o que tem aqui", "me diga o que tem aqui"
        )

        if (!mentionsScreen) return false

        return listOf(
            "leia", "ler", "resuma", "resumir", "analise", "analisar",
            "explique", "explicar", "interprete", "interpretar",
            "o que tem", "o que esta", "o que aparece", "leia isso", "le isso",
            "o que estou vendo", "o que eu estou vendo", "o que tem aqui", "o que aparece aqui"
        ).any { it in c }
    }

    fun requiresReasoning(raw: String): Boolean {
        val c = normalize(raw)
        return listOf(
            "analise", "analisar", "explique", "explicar",
            "interprete", "interpretar", "o que significa",
            "resuma", "resumir", "compare", "resolva"
        ).any { it in c }
    }

    fun captureTextAsync(context: Context, callback: (String) -> Unit) {
        val app = context.applicationContext
        thread(name = "gama-local-vision") {
            callback(runCatching { captureCombinedText(app) }.getOrDefault(""))
        }
    }

    fun captureCombinedText(context: Context): String {
        val nativeText = GamaScreenContextService.snapshotNow()
            ?.plainText(5000)
            .orEmpty()
            .trim()

        val bitmap = GamaScreenContextService.captureCurrentBitmap()
        val ocr = if (bitmap == null) {
            ""
        } else {
            try {
                extractText(bitmap)
            } finally {
                runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
            }
        }

        return merge(nativeText, ocr)
    }

    fun extractText(bitmap: Bitmap): String {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ""

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = AtomicReference("")
        val latch = CountDownLatch(1)

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val lines = LinkedHashSet<String>()
                    text.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val clean = line.text.replace(Regex("\\s+"), " ").trim()
                            if (clean.isNotBlank()) lines.add(clean)
                        }
                    }
                    result.set(lines.joinToString("\n").take(MAX_TEXT))
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }

            if (!latch.await(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return ""
            return result.get().trim()
        } finally {
            runCatching { recognizer.close() }
        }
    }

    fun spokenReadReply(text: String): String =
        if (text.isBlank()) "Não encontrei texto legível nesta tela."
        else "Na tela está escrito: ${text.take(3000)}"

    private fun merge(nativeText: String, ocrText: String): String {
        val lines = LinkedHashSet<String>()
        sequenceOf(nativeText, ocrText)
            .filter { it.isNotBlank() }
            .forEach { part ->
                part.lines()
                    .map { it.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotBlank() }
                    .forEach(lines::add)
            }
        return lines.joinToString("\n").take(MAX_TEXT)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
