package com.gama.assistant

import android.content.Context
import java.text.Normalizer
import java.util.Locale

object ZevronCalendarFlow {

    private const val PREFS =
        "zevron_calendar_flow"

    private const val KEY_PENDING =
        "pending_command"

    private const val KEY_TIME =
        "pending_time"

    private const val TTL_MS =
        15L * 60L * 1000L

    fun handle(
        context: Context,
        raw: String
    ): String? {

        val canonical =
            canonicalizeNumbers(
                raw
            )

        val normalized =
            normalize(
                canonical
            )
                .removePrefix(
                    "gama "
                )
                .trim()

        if (
            normalized in
            setOf(
                "cancela",
                "cancelar",
                "esquece",
                "deixa pra la",
                "deixa para la"
            )
        ) {

            if (
                pending(context) !=
                null
            ) {

                clear(context)

                return "Certo. Cancelei."
            }

            return null
        }

        val pending =
            pending(context)

        if (
            pending != null &&
            looksTemporal(
                normalized
            ) &&
            !looksCalendarCommand(
                normalized
            )
        ) {

            val combined =
                "$pending $canonical"

            val missing =
                missing(
                    combined
                )

            if (
                missing ==
                Missing.NONE
            ) {

                clear(context)

                return GamaCalendarManager
                    .handle(
                        context,
                        combined
                    )
                    ?: "Não consegui concluir esse compromisso."
            }

            save(
                context,
                combined
            )

            return prompt(
                missing
            )
        }

        if (
            !looksCalendarCommand(
                normalized
            )
        ) {
            return null
        }

        if (
            looksCalendarQuery(
                normalized
            )
        ) {

            return GamaCalendarManager
                .handle(
                    context,
                    canonical
                )
        }

        val missing =
            missing(
                canonical
            )

        if (
            missing !=
            Missing.NONE
        ) {

            save(
                context,
                canonical
            )

            return prompt(
                missing
            )
        }

        clear(context)

        return GamaCalendarManager
            .handle(
                context,
                canonical
            )
    }

    enum class Missing {
        NONE,
        DATE,
        TIME,
        DATE_AND_TIME
    }

    internal fun missing(
        raw: String
    ): Missing {

        val normalized =
            normalize(
                canonicalizeNumbers(
                    raw
                )
            )

        val date =
            hasDate(
                normalized
            )

        val time =
            hasTime(
                normalized
            )

        return when {

            date &&
            time ->
                Missing.NONE

            date ->
                Missing.TIME

            time ->
                Missing.DATE

            else ->
                Missing.DATE_AND_TIME
        }
    }

    private fun prompt(
        missing: Missing
    ): String {

        return when (
            missing
        ) {

            Missing.DATE_AND_TIME ->
                "Que dia e horário?"

            Missing.DATE ->
                "Para qual dia?"

            Missing.TIME ->
                "Que horário?"

            Missing.NONE ->
                "Certo."
        }
    }

    private fun save(
        context: Context,
        command: String
    ) {

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_PENDING,
                command
            )
            .putLong(
                KEY_TIME,
                System.currentTimeMillis()
            )
            .apply()
    }

    private fun pending(
        context: Context
    ): String? {

        val prefs =
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

        val time =
            prefs.getLong(
                KEY_TIME,
                0L
            )

        if (
            time <= 0L ||
            System.currentTimeMillis() -
            time >
            TTL_MS
        ) {

            clear(context)

            return null
        }

        return prefs.getString(
            KEY_PENDING,
            null
        )
    }

    private fun clear(
        context: Context
    ) {

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .clear()
            .apply()
    }

    private fun looksCalendarCommand(
        value: String
    ): Boolean {

        return listOf(
            "agenda",
            "calendario",
            "compromisso",
            "evento",
            "marque",
            "marca",
            "marcar",
            "agende",
            "agendar",
            "adicione",
            "adicionar",
            "coloque",
            "colocar",
            "anote",
            "anotar",
            "lembre",
            "lembrar",
            "lembrete",
            "reserve",
            "reservar"
        )
            .any {
                Regex(
                    "\\b$it\\b"
                )
                    .containsMatchIn(
                        value
                    )
            }
    }

    private fun looksCalendarQuery(
        value: String
    ): Boolean {

        return listOf(
            "o que tenho",
            "tenho algo",
            "minha agenda",
            "leia minha agenda",
            "agenda hoje",
            "compromissos hoje",
            "eventos hoje",
            "o que esta marcado"
        )
            .any {
                it in value
            }
    }

    private fun looksTemporal(
        value: String
    ): Boolean {

        return hasDate(value) ||
            hasTime(value)
    }

    private fun hasDate(
        value: String
    ): Boolean {

        if (
            listOf(
                "hoje",
                "amanha",
                "depois de amanha",
                "segunda",
                "terca",
                "quarta",
                "quinta",
                "sexta",
                "sabado",
                "domingo"
            )
                .any {
                    it in value
                }
        ) {
            return true
        }

        if (
            Regex(
                """\bdia\s+\d{1,2}\b"""
            )
                .containsMatchIn(
                    value
                )
        ) {
            return true
        }

        if (
            Regex(
                """\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b"""
            )
                .containsMatchIn(
                    value
                )
        ) {
            return true
        }

        return false
    }

    private fun hasTime(
        value: String
    ): Boolean {

        if ("meio dia" in value || "meia noite" in value) return true

        return Regex(
            """\b(?:as\s+|a\s+)?\d{1,2}(?:(?:h|:)\d{0,2}|\s+horas?)\b"""
        )
            .containsMatchIn(
                value
            )
    }

    internal fun canonicalizeNumbers(
        raw: String
    ): String {

        var value =
            raw

        val numbers =
            linkedMapOf(
                "uma" to "1",
                "duas" to "2",
                "dois" to "2",
                "tres" to "3",
                "quatro" to "4",
                "cinco" to "5",
                "seis" to "6",
                "sete" to "7",
                "oito" to "8",
                "nove" to "9",
                "dez" to "10",
                "onze" to "11",
                "doze" to "12",
                "treze" to "13",
                "quatorze" to "14",
                "catorze" to "14",
                "quinze" to "15",
                "dezesseis" to "16",
                "dezessete" to "17",
                "dezoito" to "18",
                "dezenove" to "19",
                "vinte" to "20",
                "vinte e uma" to "21",
                "vinte e duas" to "22",
                "vinte e tres" to "23"
            )

        value =
            Normalizer.normalize(
                value.lowercase(
                    Locale.ROOT
                ),
                Normalizer.Form.NFD
            )
                .replace(
                    Regex("\\p{M}+"),
                    ""
                )

        value =
            value.replace(
                Regex(
                    """\b(?:as|a)\s+(uma|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|quatorze|catorze|quinze|dezesseis|dezessete|dezoito|dezenove|vinte|vinte e uma|vinte e duas|vinte e tres)\s+e\s+meia\b"""
                )
            ) {
                val word =
                    it.groupValues[1]

                val number =
                    numbers[word]
                        ?: word

                "as ${number}h30"
            }

        for (
            entry in
            numbers.entries.sortedByDescending {
                it.key.length
            }
        ) {

            value =
                value.replace(
                    Regex(
                        """\b(?:as|a)\s+${Regex.escape(entry.key)}(?:\s+horas?)?\b"""
                    ),
                    "as ${entry.value}h"
                )
        }

        return value
    }

    internal fun normalize(
        value: String
    ): String {

        return Normalizer.normalize(
            value.lowercase(
                Locale.ROOT
            ),
            Normalizer.Form.NFD
        )
            .replace(
                Regex("\\p{M}+"),
                ""
            )
            .replace(
                Regex(
                    "[^a-z0-9:/ ]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }
}
