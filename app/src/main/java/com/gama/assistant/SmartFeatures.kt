package com.gama.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Acessa apenas calendário local autorizado e previsão solicitada explicitamente. */
object SmartFeatures {
    private const val PREFERENCES = "gama_live_services_v1"
    private val weatherWords = listOf("previsao do tempo", "tempo em ", "clima em ", "vai chover", "como esta o tempo", "como esta o clima", "temperatura em ", "qual a temperatura")
    private val calendarWords = listOf(
        "minha agenda", "meus compromissos", "meus eventos", "proximo compromisso",
        "proximo evento", "proximos compromissos", "proximos eventos", "compromissos de hoje",
        "eventos de hoje", "o que tenho hoje", "agenda de hoje", "agenda de amanha",
        "compromissos de amanha", "eventos de amanha", "tenho algum compromisso",
        "tenho algum evento", "tenho compromissos", "tenho eventos",
        "tem algo marcado para hoje", "tenho algo marcado para hoje", "algo marcado para hoje",
        "tem alguma coisa marcada para hoje", "alguma coisa marcada para hoje",
        "ha algo marcado para hoje", "algum compromisso hoje", "algum evento hoje"
    )
    private val briefingWords = setOf("resumo do dia", "resumo de hoje", "planeje meu dia", "como sera meu dia", "briefing do dia", "meu dia")

    fun isWeather(c: String): Boolean = weatherWords.any { c.startsWith(it) || c.contains(it) }
    fun isCalendar(c: String): Boolean {
        val n = c.trim()
        if (calendarWords.any { n == it || n.startsWith("quais sao $it") || n.contains(it) }) return true
        val hasCalendarNoun = listOf("agenda", "compromisso", "compromissos", "evento", "eventos")
            .any { n.contains(it) }
        val personalOrTime = listOf(
            "tenho", "minha", "meu", "meus", "minhas", "proximo", "proximos",
            "hoje", "amanha", "semana", "nos proximos dias", "proximos dias", "dias seguintes"
        ).any { n.contains(it) }
        val markedTime = n.contains("marcad") && listOf(
            "hoje", "amanha", "semana", "proximos dias", "dias seguintes"
        ).any { n.contains(it) }
        val nearbyTime = listOf(
            "por esses dias", "esses dias", "essa semana", "esta semana",
            "nos proximos dias", "proximos dias", "dias seguintes"
        ).any { n.contains(it) }
        val importantUpcoming = nearbyTime &&
            listOf("algo importante", "alguma coisa importante", "coisa importante", "compromisso importante", "evento importante")
                .any { n.contains(it) }
        return (hasCalendarNoun && personalOrTime) || markedTime || importantUpcoming
    }
    fun isBriefing(c: String): Boolean = c in briefingWords
    fun isAsync(c: String): Boolean = isWeather(c) || isCalendar(c) || isBriefing(c)
    fun isLocal(c: String): Boolean = c.startsWith("defina minha cidade como ") ||
        c.startsWith("minha cidade e ") || c in setOf("qual minha cidade", "apague minha cidade") ||
        c.startsWith("criar compromisso ") || c.startsWith("crie compromisso ") || c.startsWith("adicionar compromisso ") || c.startsWith("marcar compromisso ") || c.startsWith("marque compromisso ")

    fun localAction(service: GamaService, c: String, raw: String): String? {
        val prefix = listOf("defina minha cidade como ", "minha cidade e ").firstOrNull { c.startsWith(it) }
        if (prefix != null) {
            // Normalizado apenas para detectar comando; preservamos a grafia enviada pelo usuário.
            val city = raw.trim().split(Regex("\\s+"), limit = if (prefix == "minha cidade e ") 4 else 5)
                .lastOrNull().orEmpty().trim().take(80)
            if (city.length < 2 || city.any { Character.isISOControl(it) }) return "Informe uma cidade válida."
            service.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("city", city).apply()
            return "Cidade de referência salva: $city. Para consultar o clima, precisarei de internet."
        }
        if (c == "qual minha cidade") {
            val city = service.getSharedPreferences(PREFERENCES, 0).getString("city", "").orEmpty()
            return if (city.isBlank()) "Nenhuma cidade configurada. Diga: defina minha cidade como Aracaju." else "Cidade de referência: $city."
        }
        if (c == "apague minha cidade") {
            service.getSharedPreferences(PREFERENCES, 0).edit().remove("city").apply()
            return "Cidade de referência apagada."
        }
        val eventPrefix = listOf("criar compromisso ", "crie compromisso ", "adicionar compromisso ", "marcar compromisso ", "marque compromisso ").firstOrNull { c.startsWith(it) }
        if (eventPrefix != null) {
            val title = c.removePrefix(eventPrefix).trim().take(140)
            if (title.isBlank()) return "Diga o nome do compromisso que deseja criar."
            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
            // Não criamos nem confirmamos o evento: a pessoa precisa aprovar no calendário.
            return service.launchSafely(intent)
        }
        return null
    }

    fun respond(context: Context, c: String): String = when {
        isBriefing(c) -> briefing(context)
        isCalendar(c) -> calendar(context, c)
        isWeather(c) -> weather(context, c)
        else -> "Comando de dados não reconhecido."
    }

    private fun calendar(context: Context, command: String): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Não tenho permissão para ler a agenda. Abra Ajustes do Gama e permita o acesso ao calendário."
        }
        val now = System.currentTimeMillis()
        val normalized = command.trim()
        val tomorrowOnly = normalized.contains("amanha") && !normalized.contains("depois de amanha")
        val todayOnly = normalized.contains("hoje") && !normalized.contains("proximos dias")
        val weekWindow = normalized.contains("semana") || normalized.contains("proximos dias") ||
            normalized.contains("dias seguintes") || normalized.contains("nos proximos dias") ||
            normalized.contains("por esses dias") || normalized.contains("esses dias")
        val nextSingle = normalized.contains("proximo compromisso") || normalized.contains("proximo evento")

        val midnightToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val begin: Long
        val end: Long
        val period: String
        when {
            tomorrowOnly -> {
                val start = (midnightToday.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                val stop = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                begin = start.timeInMillis
                end = stop.timeInMillis
                period = "de amanhã"
            }
            todayOnly -> {
                val stop = (midnightToday.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                begin = now
                end = stop.timeInMillis
                period = "de hoje"
            }
            weekWindow -> {
                begin = now
                end = now + 7L * 24L * 3600_000L
                period = "dos próximos 7 dias"
            }
            nextSingle -> {
                begin = now
                end = now + 14L * 24L * 3600_000L
                period = "das próximas duas semanas"
            }
            else -> {
                begin = now
                end = now + 48L * 3600_000L
                period = "das próximas 48 horas"
            }
        }

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY
        )
        return try {
            val entries = mutableListOf<Pair<Long, String>>()
            CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { cur ->
                while (cur.moveToNext() && entries.size < 8) {
                    val start = cur.getLong(1)
                    val title = cur.getString(0)?.trim().orEmpty().ifBlank { "Compromisso sem título" }.take(80)
                    val allDay = cur.getInt(2) == 1
                    val label = if (allDay) {
                        SimpleDateFormat("EEE dd/MM, 'dia inteiro'", Locale("pt", "BR")).format(Date(start))
                    } else {
                        SimpleDateFormat("EEE dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(start))
                    }
                    entries += start to "$label: $title"
                }
            } ?: return "Não há um calendário disponível neste aparelho."

            val sorted = entries.sortedBy { it.first }
            if (sorted.isEmpty()) {
                "Não encontrei compromissos no calendário $period."
            } else if (nextSingle) {
                "Seu próximo compromisso é ${sorted.first().second}."
            } else {
                val warning = if (sorted.first().first - now in 0..(45 * 60_000L)) {
                    " Seu próximo compromisso começa em menos de 45 minutos."
                } else ""
                "Agenda $period: ${sorted.joinToString("; ") { it.second }}.$warning"
            }
        } catch (_: SecurityException) {
            "O Android não autorizou a leitura do calendário. Verifique a permissão nos ajustes."
        } catch (_: Exception) {
            "Não consegui consultar o calendário deste aparelho agora."
        }
    }

    private fun city(context: Context, command: String): String {
        val parsed = listOf("tempo em ", "clima em ", "temperatura em ").firstNotNullOfOrNull { marker ->
            command.substringAfter(marker, "").trim().takeIf { command.contains(marker) && it.isNotBlank() }
        }
        return (parsed ?: context.getSharedPreferences(PREFERENCES, 0).getString("city", "").orEmpty()).take(80)
    }

    private fun json(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 6000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("Serviço indisponível")
            return conn.inputStream.bufferedReader().use { input -> JSONObject(input.readText().take(80_000)) }
        } finally { conn.disconnect() }
    }

    private fun weather(context: Context, command: String): String {
        val name = city(context, command)
        if (name.isBlank()) return "Para consultar a previsão, diga 'tempo em Aracaju' ou defina sua cidade nos ajustes."
        return try {
            val encoded = URLEncoder.encode(name.substringBefore(',').trim(), "UTF-8")
            val places = json("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=pt&format=json")
            val item = places.optJSONArray("results")?.optJSONObject(0) ?: return "Não encontrei a cidade $name. Informe cidade e estado."
            val lat = item.getDouble("latitude"); val lon = item.getDouble("longitude")
            val locationName = listOf(item.optString("name"), item.optString("admin1")).filter { it.isNotBlank() }.joinToString(", ")
            val forecast = json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&daily=precipitation_probability_max&timezone=auto&forecast_days=1")
            val current = forecast.getJSONObject("current")
            val temperature = current.getDouble("temperature_2m").toInt()
            val code = current.optInt("weather_code", -1)
            val description = when (code) { 0 -> "céu limpo"; 1,2,3 -> "nebulosidade variável"; 45,48 -> "neblina"; 51,53,55,56,57,61,63,65,66,67,80,81,82 -> "chuva ou garoa"; 71,73,75,77,85,86 -> "possibilidade de neve"; 95,96,99 -> "trovoadas"; else -> "condições variáveis" }
            val chance = forecast.optJSONObject("daily")?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
            val date = current.optString("time", "").take(16).replace('T', ' ')
            val advice = if (chance >= 60) " Sugiro levar um guarda-chuva." else ""
            val rain = if (chance in 0..100) " Chance máxima de precipitação hoje: $chance por cento.$advice" else ""
            "Previsão consultada para $locationName, às $date: $temperature graus, $description.$rain Fonte: Open-Meteo."
        } catch (_: Exception) {
            "Não consegui consultar a previsão de $name. Verifique a internet ou tente novamente."
        }
    }

    private fun briefing(context: Context): String {
        val agenda = calendar(context, "minha agenda")
        val place = context.getSharedPreferences(PREFERENCES, 0).getString("city", "").orEmpty()
        val climate = if (place.isBlank()) "Clima não consultado: configure sua cidade." else weather(context, "previsao do tempo")
        return "$agenda $climate".take(1300)
    }
}
