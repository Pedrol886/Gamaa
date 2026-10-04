package com.gama.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Arquivo grande: obtido com consentimento, nunca embutido no APK. */
object AiSetup {
    @Volatile private var busy = false
    private const val SHA = "e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee"
    private const val URL = "https://huggingface.co/nikhil2024/gemma3-1b-it-litert-mirror/resolve/main/gemma3-1b-it-int4.task"
    private const val PART = "gemma-download.part"
    private const val MODEL = "gemma.task"
    private fun folder(ctx: Context): File? = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    private fun hasNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasSpace(ctx: Context): Boolean {
        val dir = folder(ctx) ?: return false
        return runCatching {
            StatFs(dir.absolutePath).availableBytes >= 750_000_000L
        }.getOrDefault(false)
    }
    private fun installedFile(ctx: Context): File? = listOfNotNull(File(ctx.filesDir, MODEL), folder(ctx)?.let { File(it, MODEL) })
        .filter { it.isFile && it.length() > 500_000_000L }
        .maxByOrNull { it.lastModified() }

    fun isInstalled(ctx: Context): Boolean = installedFile(ctx) != null

    private fun signature(file: File): String = "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    fun markWorking(ctx: Context, model: File) {
        ctx.getSharedPreferences("gama_setup", Context.MODE_PRIVATE).edit()
            .putString("brain_verified", signature(model))
            .remove("brain_runtime_error").apply()
    }

    fun markFailure(ctx: Context, message: String) {
        ctx.getSharedPreferences("gama_setup", Context.MODE_PRIVATE).edit()
            .remove("brain_verified").putString("brain_runtime_error", message.take(200)).apply()
    }

    fun download(activity: Activity) {
        if (busy) return
        if (isInstalled(activity)) {
            AlertDialog.Builder(activity).setTitle("Modelo de IA encontrado")
                .setMessage("O arquivo já está no celular. Se a conversa não funcionar, você pode baixar uma cópia nova para reparar a instalação. O modelo atual só será substituído após verificar o novo download.")
                .setPositiveButton("Baixar novamente") { _, _ -> enqueueDownload(activity) }
                .setNegativeButton("Cancelar", null).show()
            return
        }
        enqueueDownload(activity)
    }

    private fun enqueueDownload(activity: Activity) {
        val prefs = activity.getSharedPreferences("gama_setup", Context.MODE_PRIVATE)
        if (prefs.getLong("brain_download_id", -1L) >= 0L) {
            AlertDialog.Builder(activity).setTitle("Download em andamento")
                .setMessage("Acompanhe o progresso na tela inicial. Se falhar, aparecerá a opção de tentar novamente.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Instalar IA offline")
            .setMessage("O Gama precisa baixar um modelo Gemma 3 1B de aproximadamente 555 MB. Prefira Wi-Fi e deixe espaço livre no aparelho. Depois do download, a conversa funciona sem enviar suas perguntas à internet. A execução pode ser limitada pela memória do celular. Ao baixar, você aceita os termos da licença Gemma.")
            .setNeutralButton("Ler licença") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemma/terms"))) }
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Baixar modelo") { _, _ ->
                val ctx = activity.applicationContext
                try {
                    val directory = folder(ctx) ?: error("Armazenamento externo indisponível")
                    if (!hasSpace(ctx)) {
                        prefs.edit().putString(
                            "brain_error",
                            "Espaço insuficiente. Libere pelo menos 750 MB e tente novamente."
                        ).apply()
                        return@setPositiveButton
                    }
                    if (!hasNetwork(ctx)) {
                        prefs.edit().putString(
                            "brain_error",
                            "Sem conexão validada. Você também pode importar o arquivo .task do celular."
                        ).apply()
                        return@setPositiveButton
                    }
                    File(directory, PART).delete()
                    val request = DownloadManager.Request(Uri.parse(URL))
                        .setTitle("Gama: modelo de conversa offline")
                        .setDescription("Instalando Gemma 3 1B")
                        .setAllowedNetworkTypes(
                            DownloadManager.Request.NETWORK_WIFI or
                                DownloadManager.Request.NETWORK_MOBILE
                        )
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                        .addRequestHeader("User-Agent", "GAMA/8.2.2 Android")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, PART)
                    val id = ctx.getSystemService(DownloadManager::class.java).enqueue(request)
                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putLong("brain_download_id", id)
                        .putLong("brain_download_last_bytes", 0L)
                        .putLong("brain_download_last_progress_ms", now)
                        .remove("brain_error")
                        .apply()
                    AssistantRuntime.state("Conectando para instalar a IA offline")
                } catch (e: Exception) {
                    prefs.edit().putString("brain_error", "Falha ao iniciar o download: ${e.javaClass.simpleName}").apply()
                }
            }.show()
    }

    fun status(ctx: Context): String {
        if (busy) return "IA: verificando ou importando o modelo..."
        val prefs = ctx.getSharedPreferences("gama_setup", Context.MODE_PRIVATE)
        val model = installedFile(ctx)
        val id = prefs.getLong("brain_download_id", -1L)
        if (model != null && id < 0L) {
            if (prefs.getString("brain_verified", "") == signature(model))
                return "IA offline: testada e pronta para conversa por voz."
            val problem = prefs.getString("brain_runtime_error", null)
            return if (problem.isNullOrBlank())
                "Modelo instalado. Diga ‘Gama’ e faça uma pergunta para testar a IA por voz."
            else "Modelo presente, mas falhou ao executar: $problem"
        }
        if (id < 0L) return prefs.getString("brain_error", null)?.let { "IA: $it" }
            ?: "IA de conversa não instalada. Toque em Instalar IA offline."
        return try {
            ctx.getSystemService(DownloadManager::class.java)
                .query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use "IA: download indisponível. Abra o app para tentar novamente."
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> "IA: download concluído. Conferindo arquivo..."
                        DownloadManager.STATUS_FAILED -> "IA: o download falhou. Toque em Instalar IA para tentar novamente."
                        DownloadManager.STATUS_PAUSED -> "IA: download pausado pelo Android (${downloaded / 1_000_000} MB)."
                        else -> if (total > 0L)
                            "IA: baixando ${downloaded * 100L / total}% (${downloaded / 1_000_000} MB de ${total / 1_000_000} MB)"
                        else if (downloaded > 0L)
                            "IA: baixando ${downloaded / 1_000_000} MB..."
                        else
                            "IA: conectando ao servidor do modelo..."
                    }
                }
        } catch (_: Exception) { "IA: verificando estado do download..." }
    }

    fun check(context: Context) {
        if (busy) return
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences("gama_setup", Context.MODE_PRIVATE)
        val id = prefs.getLong("brain_download_id", -1L)
        if (id < 0L) return
        try {
            ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    prefs.edit().remove("brain_download_id").putString("brain_error", "Download desapareceu. Tente novamente.").apply()
                    return
                }
                val state = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ).coerceAtLeast(0L)
                    val now = System.currentTimeMillis()
                    val previous = prefs.getLong("brain_download_last_bytes", 0L)
                    val lastProgress = prefs.getLong("brain_download_last_progress_ms", now)

                    if (state == DownloadManager.STATUS_PENDING || state == DownloadManager.STATUS_RUNNING) {
                        if (downloaded > previous) {
                            prefs.edit()
                                .putLong("brain_download_last_bytes", downloaded)
                                .putLong("brain_download_last_progress_ms", now)
                                .apply()
                        } else if (
                            GamaModelInstallPolicy.isStalled(
                                now,
                                lastProgress,
                                downloaded,
                                previous
                            )
                        ) {
                            runCatching {
                                ctx.getSystemService(DownloadManager::class.java).remove(id)
                            }
                            folder(ctx)?.let { File(it, PART).delete() }
                            prefs.edit()
                                .remove("brain_download_id")
                                .remove("brain_download_last_bytes")
                                .remove("brain_download_last_progress_ms")
                                .putString(
                                    "brain_error",
                                    "O download ficou sem progresso por alguns minutos. Tente novamente ou importe o arquivo .task do celular."
                                )
                                .apply()
                            return
                        }
                    }

                    when (state) {
                        DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        prefs.edit().remove("brain_download_id").putString("brain_error", "Download falhou (código $reason). Tente novamente com Wi-Fi.").apply()
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        busy = true
                        thread(name = "gama-verify-model") {
                            val partial = folder(ctx)?.let { File(it, PART) }
                            try {
                                val file = partial ?: error("Arquivo não encontrado")
                                kotlin.check(file.isFile && file.length() > 500_000_000L)
                                val md = MessageDigest.getInstance("SHA-256")
                                file.inputStream().use { stream ->
                                    val bytes = ByteArray(65_536)
                                    while (true) {
                                        val n = stream.read(bytes)
                                        if (n < 0) break
                                        md.update(bytes, 0, n)
                                    }
                                }
                                val digest = md.digest().joinToString("") { "%02x".format(it) }
                                kotlin.check(digest == SHA) { "A conferência SHA-256 falhou" }
                                val target = File(file.parentFile, MODEL)
                                // Não apagar a única cópia funcional antes de concluir a troca.
                                val backup = File(file.parentFile, "gemma-previous.bak")
                                backup.delete()
                                if (target.exists()) kotlin.check(target.renameTo(backup))
                                if (!file.renameTo(target)) {
                                    backup.renameTo(target)
                                    error("Não foi possível instalar o arquivo")
                                }
                                backup.delete()
                                prefs.edit().remove("brain_download_id").remove("brain_error")
                                    .remove("brain_verified").remove("brain_runtime_error").apply()
                                AssistantRuntime.add(false, "Modelo conferido. Diga Gama e faça uma pergunta para testar a conversa por voz.")
                            } catch (e: Exception) {
                                partial?.delete()
                                prefs.edit().remove("brain_download_id")
                                    .putString("brain_error", "Falha ao conferir o modelo (${e.message ?: "arquivo inválido"}). Tente novamente.").apply()
                            } finally { busy = false }
                        }
                    }
                }
            }
        } catch (_: Exception) { /* Estado temporariamente indisponível: próxima verificação tenta novamente. */ }
    }

    fun importModel(context: Context, uri: Uri) {
        if (busy) return
        val ctx = context.applicationContext
        busy = true
        thread(name = "gama-import-model") {
            val target = File(ctx.filesDir, MODEL)
            val partial = File(ctx.filesDir, "gemma-import.part")
            try {
                ctx.contentResolver.openInputStream(uri).use { input ->
                    kotlin.check(input != null) { "Arquivo não encontrado" }
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            kotlin.check(total <= 2_000_000_000L) { "Arquivo acima do limite" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                kotlin.check(partial.length() > 500_000_000L) { "Arquivo menor que um modelo Gemma 3 1B válido" }
                val backup = File(ctx.filesDir, "gemma-previous.bak")
                backup.delete()
                if (target.exists()) kotlin.check(target.renameTo(backup))
                if (!partial.renameTo(target)) {
                    backup.renameTo(target)
                    error("Não foi possível instalar o modelo importado")
                }
                backup.delete()
                ctx.getSharedPreferences("gama_setup", Context.MODE_PRIVATE).edit()
                    .remove("brain_error").remove("brain_verified").remove("brain_runtime_error").apply()
                AssistantRuntime.add(false, "Arquivo importado. Diga Gama e faça uma pergunta para verificar a IA por voz.")
            } catch (e: Exception) {
                partial.delete()
                ctx.getSharedPreferences("gama_setup", Context.MODE_PRIVATE).edit()
                    .putString("brain_error", "Importação falhou: ${e.message ?: "arquivo indisponível"}").apply()
            } finally { busy = false }
        }
    }
}
