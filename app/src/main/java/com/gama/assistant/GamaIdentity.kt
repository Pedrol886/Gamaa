package com.gama.assistant

import android.content.Context

/** Identidade editorial pública do aplicativo. */
object GamaCreator {
    const val PUBLIC_NAME = "Pedro Lucas"
}

/**
 * Perfil local desta instalação.
 * Nome/tratamento ficam em preferências locais. O perfil facial opcional do dono
 * é mantido separadamente por GamaOwnerFaceProfile como assinatura geométrica
 * criptografada, nunca como foto bruta. PIN/digital continuam sob controle do Android.
 */
object OwnerIdentity {
    private const val STORAGE = "gama_install_owner_v2"
    private const val LEGACY_STORAGE = "gama_install_owner_v1"
    val treatments = setOf("Senhor", "Senhora")

    private fun store(context: Context) =
        context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)

    private fun legacy(context: Context) =
        context.getSharedPreferences(LEGACY_STORAGE, Context.MODE_PRIVATE)

    fun name(context: Context): String? =
        (store(context).getString("owner_name", null)
            ?: legacy(context).getString("owner_name", null))
            ?.trim()?.takeIf { it.isNotBlank() }

    fun treatment(context: Context): String {
        val value = store(context).getString("owner_treatment", null)
        return value?.takeIf { it in treatments } ?: "Senhor"
    }

    fun hasConfiguredTreatment(context: Context): Boolean =
        store(context).getString("owner_treatment", null)?.let { it in treatments } == true

    fun address(context: Context): String? =
        name(context)?.let { "${treatment(context)} $it" }

    fun hasProfile(context: Context): Boolean = name(context) != null

    /** Chamar somente após RESULT_OK da confirmação oficial do Android. */
    fun saveVerified(context: Context, rawName: String): Boolean =
        saveVerified(context, rawName, treatment(context))

    /** Chamar somente após RESULT_OK da confirmação oficial do Android. */
    fun saveVerified(context: Context, rawName: String, rawTreatment: String): Boolean {
        val cleanName = rawName.trim().replace(Regex("\\s+"), " ")
        val cleanTreatment = rawTreatment.trim().replaceFirstChar { it.uppercase() }
        if (cleanName.length !in 2..60 || cleanName.any { Character.isISOControl(it) }) return false
        if (cleanTreatment !in treatments) return false
        store(context).edit()
            .putString("owner_name", cleanName)
            .putString("owner_treatment", cleanTreatment)
            .apply()
        return true
    }

    /**
     * Perfil de foco vocal local. Ele serve apenas para priorizar a voz cadastrada
     * em ambientes com várias pessoas. Nunca substitui PIN, biometria ou credencial
     * oficial do Android para dados e ações privadas.
     */
    fun voiceOptIn(context: Context): Boolean =
        context.getSharedPreferences("muffin_owner_voice_v3", Context.MODE_PRIVATE)
            .getBoolean("focus_enabled", false)

    fun setVoiceOptIn(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("muffin_owner_voice_v3", Context.MODE_PRIVATE)
        if (enabled) prefs.edit().putBoolean("focus_enabled", true).apply()
        else prefs.edit().clear().apply()
    }

    fun deleteLocalProfile(context: Context) {
        store(context).edit().clear().apply()
        legacy(context).edit().clear().apply()
        context.getSharedPreferences("muffin_owner_voice_v3", Context.MODE_PRIVATE)
            .edit().clear().apply()
        GamaOwnerFaceProfile.clear(context)
    }
}

/** Políticas de intenção testáveis sem bibliotecas Android. */
object IdentityIntent {
    enum class Kind { CREATOR, OWNER, NONE }

    fun kind(raw: String): Kind {
        val c = VoicePolicy.normalize(raw)
        if (c in setOf("quem criou voce", "quem criou o gama", "quem criou o gama", "quem e seu criador",
                "quem e o criador do gama", "quem e o criador do gama", "quem te desenvolveu", "quem desenvolveu o gama", "quem desenvolveu o gama",
                "quem fez voce", "quem fez o gama", "quem fez o gama", "qual e seu criador")) return Kind.CREATOR
        if (c in setOf("quem sou eu", "qual e meu nome", "qual meu nome", "como eu me chamo",
                "quem e seu dono", "quem e sua dona", "quem e o dono do gama",
                "quem e a dona do gama", "quem e o proprietario do celular",
                "quem e a proprietaria do celular", "quem e o dono deste celular",
                "quem e a dona deste celular", "quem e o usuario cadastrado")) return Kind.OWNER
        return Kind.NONE
    }

    /** Comandos cujas respostas ou ações podem revelar/alterar dados pessoais. */
    fun requiresDeviceProof(raw: String): Boolean {
        val c = VoicePolicy.normalize(raw)
        if (kind(c) == Kind.OWNER) return true
        if (WhatsAppCommandParser.looksLikeSendCommand(c)) return true
        if (c.startsWith("me chame de ") || c.startsWith("mude meu nome") ||
            c.startsWith("altere meu nome") || c.startsWith("mude meu tratamento")) return true
        val personal = listOf(
            "minha agenda", "meus compromissos", "compromissos de hoje",
            "agenda de hoje", "agenda de amanha", "compromissos de amanha", "proximo compromisso",
            "proximos compromissos", "o que tenho hoje", "resumo do dia", "resumo de hoje",
            "planeje meu dia", "como sera meu dia", "briefing do dia", "meu dia",
            "minhas mensagens", "ler mensagens", "leia as mensagens", "quais mensagens",
            "quais notificacoes", "minhas notificacoes", "ler notificacoes", "leia as notificacoes",
            "notificacoes", "mensagens", "minhas notas", "anote ", "leia minhas notas",
            "minha memoria", "o que voce lembra de mim", "o que voce sabe sobre mim",
            "apagar memoria", "apague minha memoria", "esqueca o que lembra de mim",
            "lembre que ", "qual minha cidade", "minha cidade e ", "defina minha cidade como ",
            "apague minha cidade", "criar compromisso ", "crie compromisso ",
            "adicionar compromisso ", "marcar compromisso ", "marque compromisso "
        )
        val calendarPersonal =
            listOf("agenda", "compromisso", "compromissos", "evento", "eventos").any { c.contains(it) } &&
            listOf("tenho", "minha", "meu", "meus", "minhas", "proximo", "proximos", "hoje", "amanha", "semana", "dias").any { c.contains(it) }
        val markedCalendarPersonal = c.contains("marcad") &&
            listOf("hoje", "amanha", "semana", "proximos dias", "dias seguintes").any { c.contains(it) }
        val importantUpcomingPersonal =
            listOf("algo importante", "alguma coisa importante", "coisa importante", "compromisso importante", "evento importante")
                .any { c.contains(it) } &&
            listOf("por esses dias", "esses dias", "essa semana", "esta semana", "nos proximos dias", "proximos dias", "dias seguintes")
                .any { c.contains(it) }
        return personal.any { c == it || c.startsWith(it) || c.contains(" $it") } ||
            calendarPersonal || markedCalendarPersonal || importantUpcomingPersonal ||
            MessageCommandRouter.isReadRequest(c) ||
            (c.contains("nota") && (c.contains("minha") || c.contains("apague")))
    }

    fun publicReply(raw: String): String? = when (kind(raw)) {
        Kind.CREATOR -> "O Gama foi criado por ${GamaCreator.PUBLIC_NAME}. O proprietário deste aparelho é definido separadamente no perfil local."
        else -> null
    }

    fun ownerReply(context: Context, raw: String): String? = when (kind(raw)) {
        Kind.OWNER -> OwnerIdentity.address(context)?.let { "O perfil autenticado deste aparelho está configurado como $it." }
            ?: "Ainda não existe uma identidade configurada neste aparelho. Abra Ajustes e configure o perfil."
        else -> null
    }
}
