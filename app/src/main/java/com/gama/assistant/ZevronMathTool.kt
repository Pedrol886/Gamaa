package com.gama.assistant

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

object ZevronMathTool {

    private const val PREFS =
        "zevron_math_context"

    private const val KEY_EXPRESSION =
        "last_expression"

    private const val KEY_EXPLANATION =
        "last_explanation"

    data class Answer(
        val spoken: String,
        val explanation: String,
        val expression: String
    )

    fun handle(
        context: Context,
        raw: String
    ): String? {

        val normalized =
            normalize(raw)
                .removePrefix(
                    "gama "
                )
                .trim()

        if (
            normalized in setOf(
                "por que",
                "porque",
                "como chegou nisso",
                "como voce chegou nisso",
                "explique",
                "me explique",
                "explica"
            )
        ) {

            return context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                .getString(
                    KEY_EXPLANATION,
                    null
                )
        }

        contextualReplacement(
            context,
            raw
        )
            ?.let {
                save(
                    context,
                    it
                )

                return it.spoken
            }

        val answer =
            solve(raw)
                ?: return null

        save(
            context,
            answer
        )

        return answer.spoken
    }

    private fun contextualReplacement(
        context: Context,
        raw: String
    ): Answer? {

        val normalized =
            normalize(raw)

        val match =
            Regex(
                """e se (?:o )?(-?\d+(?:[.,]\d+)?) fosse (-?\d+(?:[.,]\d+)?)"""
            )
                .find(normalized)
                ?: return null

        val oldValue =
            match.groupValues[1]
                .replace(
                    ",",
                    "."
                )

        val newValue =
            match.groupValues[2]
                .replace(
                    ",",
                    "."
                )

        val prefs =
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

        val expression =
            prefs.getString(
                KEY_EXPRESSION,
                null
            )
                ?: return null

        if (
            oldValue !in expression
        ) {
            return null
        }

        val changed =
            expression.replaceFirst(
                oldValue,
                newValue
            )

        return solveCanonical(
            changed
        )
    }

    private fun save(
        context: Context,
        answer: Answer
    ) {

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_EXPRESSION,
                answer.expression
            )
            .putString(
                KEY_EXPLANATION,
                answer.explanation
            )
            .apply()

        ZevronConversationEngine
            .rememberSubject(
                context,
                answer.expression
            )
    }

    fun solve(
        raw: String
    ): Answer? {

        var expression =
            canonical(
                ZevronConversationEngine
                    .stripWakeWord(
                        raw
                    )
            )

        expression =
            expression
                .removePrefix(
                    "quanto e "
                )
                .removePrefix(
                    "calcule "
                )
                .removePrefix(
                    "calcula "
                )
                .removePrefix(
                    "resolva "
                )
                .removePrefix(
                    "resolve "
                )
                .trim()

        return solveCanonical(
            expression
        )
    }

    private fun solveCanonical(
        expression: String
    ): Answer? {

        if (
            "=" in expression &&
            "x" in expression
        ) {

            solveQuadratic(
                expression
            )
                ?.let {
                    return it
                }

            solveLinear(
                expression
            )
                ?.let {
                    return it
                }
        }

        if (
            !looksArithmetic(
                expression
            )
        ) {
            return null
        }

        val value =
            runCatching {
                ExpressionParser(
                    expression
                )
                    .parse()
            }
                .getOrNull()
                ?: return null

        if (
            !value.isFinite()
        ) {
            return null
        }

        val pretty =
            pretty(value)

        return Answer(
            spoken = pretty,
            explanation =
                "O resultado é $pretty. Fiz o cálculo diretamente.",
            expression =
                expression
        )
    }

    private fun solveLinear(
        expression: String
    ): Answer? {

        val sides =
            expression.split("=")

        if (
            sides.size != 2
        ) {
            return null
        }

        val left =
            parseLinearSide(
                sides[0]
            )
                ?: return null

        val right =
            parseLinearSide(
                sides[1]
            )
                ?: return null

        val coefficient =
            left.first -
            right.first

        val constant =
            right.second -
            left.second

        if (
            abs(coefficient) <
            0.0000001
        ) {
            return null
        }

        val value =
            constant /
            coefficient

        val result =
            pretty(value)

        return Answer(
            spoken =
                "x é igual a $result.",
            explanation =
                "Passei os termos com x para um lado e os números para o outro. Ficou ${pretty(coefficient)}x igual a ${pretty(constant)}, então x é $result.",
            expression =
                expression
        )
    }

    private fun solveQuadratic(
        expression: String
    ): Answer? {

        val compact =
            expression
                .replace(
                    " ",
                    ""
                )
                .replace(
                    "²",
                    "^2"
                )

        val match =
            Regex(
                """([+-]?\d*(?:\.\d+)?)x\^2([+-]\d*(?:\.\d+)?)x([+-]\d*(?:\.\d+)?)=0"""
            )
                .matchEntire(
                    compact
                )
                ?: return null

        fun coefficient(
            value: String
        ): Double {

            return when (
                value
            ) {

                "",
                "+" ->
                    1.0

                "-" ->
                    -1.0

                else ->
                    value.toDouble()
            }
        }

        val a =
            coefficient(
                match.groupValues[1]
            )

        val b =
            coefficient(
                match.groupValues[2]
            )

        val c =
            match.groupValues[3]
                .toDouble()

        if (
            abs(a) <
            0.0000001
        ) {
            return null
        }

        val delta =
            b * b -
            4.0 * a * c

        if (
            delta < 0.0
        ) {

            return Answer(
                spoken =
                    "Essa equação não tem raízes reais.",
                explanation =
                    "O discriminante é ${pretty(delta)}, portanto não existem raízes reais.",
                expression =
                    expression
            )
        }

        val root =
            sqrt(delta)

        val x1 =
            (-b + root) /
            (2.0 * a)

        val x2 =
            (-b - root) /
            (2.0 * a)

        val spoken =
            if (
                abs(x1 - x2) <
                0.0000001
            ) {

                "x é igual a ${pretty(x1)}."

            } else {

                "As raízes são ${pretty(x1)} e ${pretty(x2)}."
            }

        return Answer(
            spoken =
                spoken,
            explanation =
                "Usei a fórmula de Bhaskara. O discriminante é ${pretty(delta)} e as raízes são ${pretty(x1)} e ${pretty(x2)}.",
            expression =
                expression
        )
    }

    private fun parseLinearSide(
        side: String
    ): Pair<Double, Double>? {

        val compact =
            side.replace(
                " ",
                ""
            )

        if (
            compact.isBlank()
        ) {
            return null
        }

        val signed =
            if (
                compact.startsWith(
                    "-"
                ) ||
                compact.startsWith(
                    "+"
                )
            ) {
                compact
            } else {
                "+$compact"
            }

        val terms =
            Regex(
                """[+-][^+-]+"""
            )
                .findAll(
                    signed
                )
                .map {
                    it.value
                }
                .toList()

        var x =
            0.0

        var number =
            0.0

        for (
            term in terms
        ) {

            if (
                "x" in term
            ) {

                val coefficient =
                    term.replace(
                        "x",
                        ""
                    )

                x += when (
                    coefficient
                ) {

                    "+",
                    "" ->
                        1.0

                    "-" ->
                        -1.0

                    else ->
                        coefficient
                            .toDoubleOrNull()
                            ?: return null
                }

            } else {

                number +=
                    term.toDoubleOrNull()
                        ?: return null
            }
        }

        return x to number
    }

    private fun canonical(
        raw: String
    ): String {

        var text =
            raw.lowercase(
                Locale.ROOT
            )
                .replace(
                    "²",
                    "^2"
                )

        text =
            Normalizer.normalize(
                text,
                Normalizer.Form.NFD
            )
                .replace(
                    Regex("\\p{M}+"),
                    ""
                )

        return text
            .replace(
                "×",
                "*"
            )
            .replace(
                "÷",
                "/"
            )
            .replace(
                " vezes ",
                " * "
            )
            .replace(
                " multiplicado por ",
                " * "
            )
            .replace(
                " dividido por ",
                " / "
            )
            .replace(
                " dividido ",
                " / "
            )
            .replace(
                " mais ",
                " + "
            )
            .replace(
                " menos ",
                " - "
            )
            .replace(
                ",",
                "."
            )
            .replace(
                Regex(
                    """[^a-z0-9x+\-*/^().= ]+"""
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun looksArithmetic(
        expression: String
    ): Boolean {

        if (
            !Regex("\\d")
                .containsMatchIn(
                    expression
                )
        ) {
            return false
        }

        if (
            !Regex(
                """[+\-*/^()]"""
            )
                .containsMatchIn(
                    expression
                )
        ) {
            return false
        }

        val withoutX =
            expression.replace(
                "x",
                ""
            )

        return !Regex(
            """[a-wyz]"""
        )
            .containsMatchIn(
                withoutX
            )
    }

    private fun pretty(
        value: Double
    ): String {

        val rounded =
            round(value)

        if (
            abs(
                value -
                rounded
            ) <
            0.0000001
        ) {

            return rounded
                .toLong()
                .toString()
        }

        return String.format(
            Locale.US,
            "%.6f",
            value
        )
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun normalize(
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
                    "[^a-z0-9., ]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private class ExpressionParser(
        private val input: String
    ) {

        private var index =
            0

        fun parse(): Double {

            val value =
                expression()

            skip()

            if (
                index != input.length
            ) {
                error(
                    "Entrada inesperada."
                )
            }

            return value
        }

        private fun expression(): Double {

            var value =
                term()

            while (true) {

                skip()

                value =
                    when {

                        take('+') ->
                            value +
                            term()

                        take('-') ->
                            value -
                            term()

                        else ->
                            return value
                    }
            }
        }

        private fun term(): Double {

            var value =
                power()

            while (true) {

                skip()

                value =
                    when {

                        take('*') ->
                            value *
                            power()

                        take('/') ->
                            value /
                            power()

                        else ->
                            return value
                    }
            }
        }

        private fun power(): Double {

            var value =
                unary()

            skip()

            if (
                take('^')
            ) {

                value =
                    Math.pow(
                        value,
                        power()
                    )
            }

            return value
        }

        private fun unary(): Double {

            skip()

            if (
                take('+')
            ) {
                return unary()
            }

            if (
                take('-')
            ) {
                return -unary()
            }

            return primary()
        }

        private fun primary(): Double {

            skip()

            if (
                take('(')
            ) {

                val value =
                    expression()

                if (
                    !take(')')
                ) {
                    error(
                        "Parêntese ausente."
                    )
                }

                return value
            }

            val start =
                index

            while (
                index <
                input.length &&
                (
                    input[index]
                        .isDigit() ||
                    input[index] ==
                    '.'
                )
            ) {
                index++
            }

            if (
                start ==
                index
            ) {
                error(
                    "Número esperado."
                )
            }

            return input.substring(
                start,
                index
            )
                .toDouble()
        }

        private fun skip() {

            while (
                index <
                input.length &&
                input[index]
                    .isWhitespace()
            ) {
                index++
            }
        }

        private fun take(
            char: Char
        ): Boolean {

            skip()

            if (
                index <
                input.length &&
                input[index] ==
                char
            ) {

                index++

                return true
            }

            return false
        }
    }
}
