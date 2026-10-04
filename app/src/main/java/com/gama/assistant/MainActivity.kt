package com.gama.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView
import android.graphics.drawable.GradientDrawable
import kotlin.concurrent.thread
import android.widget.Toast

/**
 * Primeira abertura: consentimento claro e individual para permissões.
 * Aberturas posteriores: controles mínimos; a conversa principal é sempre por voz, sem painel de chat nem cadastro biométrico.
 * O usuário pode sempre reabrir ajustes e pausar o microfone pela notificação.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val pref by lazy { getSharedPreferences("gama_first_run_v35", MODE_PRIVATE) }
    private lateinit var title: TextView
    private lateinit var details: TextView
    private lateinit var modelDetails: TextView
    private val modelPoll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            AiSetup.check(this@MainActivity)
            showStatus()
            maybeLaunchReadyIntro()
            ui.postDelayed(this, 2500L)
        }
    }
    private var newOwnerName: String? = null
    private var newOwnerTreatment: String = "Senhor"
    private var actionAfterCredential: String? = null
    private var setupActive = false
    private var introLaunching = false
    private val actionSettings = "com.gama.assistant.SETTINGS"
    private val actionProof = "com.gama.assistant.AUTH_OWNER"
    private val actionDeviceAdmin = "com.gama.assistant.ACTIVATE_DEVICE_ADMIN"
    private val actionContacts = "com.gama.assistant.REQUEST_CONTACTS"
    private val actionNotificationAccess = "com.gama.assistant.REQUEST_NOTIFICATION_ACCESS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Migração: desativar definitivamente o cadastro vocal anterior.
        // Não gravar novas amostras da voz nesta versão.
        if (!pref.getBoolean("voice_migration_done", false)) {
            OwnerIdentity.setVoiceOptIn(this, false)
            getSharedPreferences("muffin_owner_voice_v3", MODE_PRIVATE).edit().clear().apply()
            pref.edit().putBoolean("voice_migration_done", true).apply()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.rgb(8, 4, 7))
            setPadding(dp(22), dp(26), dp(22), dp(28))
        }
        title = TextView(this).apply {
            text = "GAMA"; textSize = 30f; setTextColor(Color.rgb(255, 49, 70))
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(4))
        }
        val subtitle = TextView(this).apply {
            text = "Seu assistente Android"; textSize = 13f
            setTextColor(Color.rgb(205, 146, 154)); gravity = Gravity.CENTER
        }
        details = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(8), dp(22), dp(8), dp(15))
        }
        modelDetails = TextView(this).apply {
            textSize = 14f; setTextColor(Color.rgb(255, 196, 92))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 9, 15)); setStroke(dp(1), Color.rgb(111, 21, 33)); cornerRadius = dp(13).toFloat()
            }
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(subtitle, ViewGroup.LayoutParams(-1, -2))
        val redCore = GamaEnergyView(this).apply {
            mode = GamaEnergyView.Mode.LISTENING
            contentDescription = "Logo do Gama"
        }
        root.addView(redCore, LinearLayout.LayoutParams(-1, dp(220)).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })
        root.addView(details, ViewGroup.LayoutParams(-1, -2))
        root.addView(modelDetails, ViewGroup.LayoutParams(-1, -2))
        fun control(text: String, primary: Boolean = false, action: () -> Unit) {
            val button = Button(this).apply {
                this.text = text; setAllCaps(false); textSize = 15f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (primary) Color.rgb(190, 18, 42) else Color.rgb(49, 10, 18)); setStroke(dp(1), if (primary) Color.rgb(255, 66, 85) else Color.rgb(112, 23, 36))
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener { action() }
            }
            root.addView(button, LinearLayout.LayoutParams(-1, dp(54)).apply {
                topMargin = dp(10)
            })
        }
        control("Ativar escuta por voz", true) { startListening(); showStatus() }
        control("Ativar bolinha flutuante") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
        }
        control("Ativar controle de tela e rolagem") {
            if (!GamaScreenContextService.openSettings(this)) {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        control("Autorizar acesso às notificações") {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        control("Ativar bloqueio de tela") { requestDeviceAdmin() }
        control("Permitir contatos para WhatsApp") { requestContactsForWhatsApp() }
        control("Instalar ou reparar IA offline") { AiSetup.download(this) }
        control("Importar IA do celular") { selectModelFile() }
        control("Pausar microfone") { BackgroundMode.pause(this); showStatus() }
        control("Permissões e ajustes") { settings() }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 4, 7))
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(modelPoll)
        ui.post(modelPoll)
        if (::details.isInitialized) showStatus()
        if (GamaNotificationCenter.hasAccess(this)) {
            AssistantRuntime.service?.resumePendingNotificationRead()
        }
        val configured = pref.getBoolean("setup_done", false)
        if (!configured && !setupActive) {
            showWelcome()
        } else if (configured && OwnerIdentity.hasProfile(this) &&
            !OwnerIdentity.hasConfiguredTreatment(this) && !setupActive) {
            setupActive = true
            newOwnerName = OwnerIdentity.name(this)
            askOwnerName()
        } else if (configured && BackgroundMode.enabled(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            AssistantRuntime.service == null) {
            // Atualizações do APK encerram o processo. Ao abrir o Gama novamente,
            // recupere a escuta automaticamente em vez de ficar preso em "iniciando".
            startListening()
        }
        maybeLaunchReadyIntro()
    }

    override fun onPause() {
        ui.removeCallbacks(modelPoll)
        super.onPause()
    }

    override fun onNewIntent(next: Intent?) {
        super.onNewIntent(next)
        setIntent(next)
        handleIntent(next)
    }

    private fun handleIntent(next: Intent?) {
        if (next?.action == actionProof) {
            actionAfterCredential = "private_command"
            requestCredential()
            return
        }
        if (next?.action == actionDeviceAdmin) {
            requestDeviceAdmin()
            return
        }
        if (next?.action == actionContacts) {
            requestContactsForWhatsApp()
            return
        }
        if (next?.action == actionNotificationAccess) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        if (next?.action == actionSettings) {
            if (!pref.getBoolean("setup_done", false)) showWelcome() else settings()
            return
        }
        if (!pref.getBoolean("setup_done", false)) {
            if (!setupActive) showWelcome()
        } else if (next?.action == Intent.ACTION_ASSIST) {
            startListening()
            finish()
        } else {
            // A tela principal permanece disponível para baixar/importar/testar a IA
            // e ativar/pausar a escuta, sem esconder indicadores do Android.
            AiSetup.check(this)
            showStatus()
        }
    }

    private fun showStatus() {
        if (!::details.isInitialized) return
        val voice = when {
            !pref.getBoolean("setup_done", false) -> "Configure o Gama uma única vez para começar."
            AssistantRuntime.service?.isVoiceReady() == true -> "Escuta ativa. Diga ‘Gama’ para começar."
            BackgroundMode.enabled(this) -> "Escuta iniciando. Se continuar assim, toque em Ativar escuta por voz."
            else -> "Escuta pausada. Ative a escuta por voz para falar com o Gama."
        }
        val orbStatus = if (Settings.canDrawOverlays(this))
            "Bolinha flutuante autorizada."
        else
            "Bolinha flutuante sem autorização. Toque em Ativar bolinha flutuante."
        val lockStatus = if (isDeviceAdminActive())
            "Bloqueio de tela autorizado."
        else
            "Bloqueio de tela sem autorização. Toque em Ativar bloqueio de tela."
        val contactStatus = if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            "Contatos autorizados para localizar conversas do WhatsApp."
        else "Contatos sem autorização. Toque em Permitir contatos para WhatsApp se quiser enviar mensagens por nome."
        val calendarStatus = if (
            checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        ) "Agenda com leitura e gravação autorizadas."
        else "Agenda sem permissão completa. Autorize calendário para criar compromissos por voz."
        val accessibilityStatus = if (GamaScreenContextService.isConnected())
            "Controle de tela autorizado."
        else "Controle de tela desativado. Toque em Ativar controle de tela e rolagem."
        val notificationStatus = if (GamaSleepBriefingStore.hasNotificationAccess(this))
            "Acesso às notificações autorizado."
        else "Acesso às notificações desativado. Toque em Autorizar acesso às notificações."
        val identity = OwnerIdentity.address(this)?.let { "Identidade: $it" } ?: "Identidade ainda não configurada."
        details.text = "$voice\n$orbStatus\n$lockStatus\n$calendarStatus\n$contactStatus\n$accessibilityStatus\n$notificationStatus\n$identity"
        if (::modelDetails.isInitialized) modelDetails.text = AiSetup.status(this)
    }

    private fun showWelcome() {
        setupActive = true
        AlertDialog.Builder(this)
            .setTitle("Configuração inicial do Gama")
            .setMessage("O Gama usa microfone quando a escuta está ligada, com indicação e notificação do Android. " +
                "Nenhuma voz será cadastrada. Seu nome fica apenas neste celular. " +
                "Pedidos privados exigirão a confirmação do Android. " +
                "Cada permissão será solicitada separadamente e pode ser revogada nas Configurações do aparelho.")
            .setCancelable(false)
            .setPositiveButton("Continuar") { _, _ -> askOwnerName() }
            .setNegativeButton("Sair") { _, _ -> setupActive = false; finish() }
            .show()
    }

    private fun askOwnerName() {
        val nameField = EditText(this).apply {
            hint = "Seu nome neste celular"; setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            setText(newOwnerName ?: OwnerIdentity.name(this@MainActivity).orEmpty())
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val senhor = RadioButton(this).apply { id = 4701; text = "Senhor" }
        val senhora = RadioButton(this).apply { id = 4702; text = "Senhora" }
        group.addView(senhor)
        group.addView(senhora)
        val current = if (newOwnerTreatment in OwnerIdentity.treatments) newOwnerTreatment
            else OwnerIdentity.treatment(this)
        group.check(if (current == "Senhora") 4702 else 4701)

        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), 0)
            addView(TextView(this@MainActivity).apply { text = "Como o Gama deve chamar você?"; setTextColor(Color.WHITE) })
            addView(group)
            addView(nameField)
            addView(TextView(this@MainActivity).apply {
                text = "Prévia: Senhor/Senhora + seu nome. A voz não é usada para identificar você."
                setTextColor(Color.rgb(205, 146, 154)); textSize = 12f
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Identidade neste aparelho")
            .setMessage("Escolha o tratamento e o nome. Para salvar, o Android confirma PIN, padrão, senha ou biometria conforme as opções do aparelho.")
            .setView(holder).setCancelable(false)
            .setPositiveButton("Confirmar no Android") { _, _ ->
                val proposed = nameField.text.toString().trim()
                val treatment = if (group.checkedRadioButtonId == 4702) "Senhora" else "Senhor"
                if (proposed.length !in 2..60) {
                    Toast.makeText(this, "Informe um nome entre 2 e 60 caracteres.", Toast.LENGTH_LONG).show()
                    askOwnerName()
                } else {
                    newOwnerName = proposed
                    newOwnerTreatment = treatment
                    actionAfterCredential = "save_owner"
                    requestCredential()
                }
            }
            .setNegativeButton("Sair") { _, _ -> setupActive = false; finish() }
            .show()
    }

    private fun requestCredential() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this).setTitle("Proteja o aparelho")
                .setMessage("Configure biometria, PIN, padrão ou senha nas opções de segurança do Android. Depois, volte ao Gama.")
                .setPositiveButton("Abrir segurança") { _, _ -> setupActive = false; open(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                .setNegativeButton("Cancelar") { _, _ -> actionAfterCredential = null; setupActive = false; finish() }
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val prompt = BiometricPrompt.Builder(this)
                    .setTitle("Gama: confirmar identidade")
                    .setSubtitle("Use biometria ou a credencial do aparelho")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(
                    CancellationSignal(),
                    mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            ui.post { completeCredentialAction(true) }
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            ui.post { completeCredentialAction(false) }
                        }
                    }
                )
                return
            } catch (_: Exception) {
                // Fallback abaixo: confirmação oficial do Android.
            }
        }

        val challenge = keyguard.createConfirmDeviceCredentialIntent(
            "Gama: confirmar no Android", "Use a credencial do aparelho para continuar")
        if (challenge == null) {
            Toast.makeText(this, "A confirmação do Android está indisponível.", Toast.LENGTH_LONG).show()
            actionAfterCredential = null
            return
        }
        try { startActivityForResult(challenge, 104) }
        catch (_: Exception) {
            actionAfterCredential = null
            Toast.makeText(this, "Não foi possível abrir a confirmação do Android.", Toast.LENGTH_LONG).show()
        }
    }

    private fun completeCredentialAction(success: Boolean) {
        val action = actionAfterCredential
        actionAfterCredential = null
        if (!success) {
            if (action == "save_owner" && !pref.getBoolean("setup_done", false)) askOwnerName()
            return
        }
        when (action) {
            "save_owner" -> {
                if (OwnerIdentity.saveVerified(this, newOwnerName.orEmpty(), newOwnerTreatment)) {
                    if (pref.getBoolean("setup_done", false)) {
                        setupActive = false
                        showStatus()
                    } else {
                        requestCorePermissions()
                    }
                } else askOwnerName()
            }
            "edit_owner" -> showOwnerEditor()
            "private_command" -> AssistantRuntime.service?.completeOwnerAndroidProof()
            "erase_profile" -> {
                OwnerIdentity.deleteLocalProfile(this)
                GamaProfile.forget(this)
                getSharedPreferences("gama_notes", MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(this, "Perfil, memória e notas apagados.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 104) {
            completeCredentialAction(resultCode == RESULT_OK)
        } else if (requestCode == 105 && setupActive) {
            finishSetup()
        } else if (requestCode == 106 && resultCode == RESULT_OK) {
            val selected = data?.data
            if (selected != null) AiSetup.importModel(this, selected)
        } else if (requestCode == 108) {
            introLaunching = false
            showStatus()
        }
    }

    private fun requestCorePermissions() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) askOptionalCalendar()
        else requestPermissions(missing.toTypedArray(), 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askOptionalCalendar()
                else AlertDialog.Builder(this).setTitle("Microfone não autorizado")
                    .setMessage("A escuta por voz ficará desligada. A permissão pode ser concedida depois nas Configurações do Android.")
                    .setPositiveButton("Continuar sem escuta") { _, _ -> askOptionalCalendar() }
                    .setNegativeButton("Revisar") { _, _ -> setupActive = false; openAppPermissions() }
                    .show()
            }
            101 -> askOptionalOverlay()
            303 -> {
                val granted = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                AssistantRuntime.service?.completeWhatsAppContactsPermission(granted)
                showStatus()
            }
        }
    }

    private fun askOptionalCalendar() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            askOptionalOverlay(); return
        }
        AlertDialog.Builder(this).setTitle("Agenda do Gama")
            .setMessage("Permita leitura e gravação do calendário para o Gama consultar sua agenda e criar compromissos diretamente por voz. O Gama só altera a agenda quando o senhor pedir.")
            .setPositiveButton("Permitir") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 101)
            }
            .setNegativeButton("Agora não") { _, _ -> askOptionalOverlay() }
            .show()
    }

    private fun askOptionalOverlay() {
        if (Settings.canDrawOverlays(this)) { finishSetup(); return }
        AlertDialog.Builder(this).setTitle("Bolinha flutuante")
            .setMessage("Para a bolinha aparecer quando o senhor disser Gama e para abrir aplicativos por voz com mais confiabilidade, autorize o Gama a aparecer sobre outros apps. A notificação do Android continuará visível enquanto o microfone estiver ativo.")
            .setPositiveButton("Abrir autorização") { _, _ ->
                try { startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")), 105) }
                catch (_: Exception) { finishSetup() }
            }
            .setNegativeButton("Dispensar") { _, _ -> finishSetup() }
            .show()
    }

    private fun finishSetup() {
        pref.edit().putBoolean("setup_done", true).apply()
        setupActive = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
            Toast.makeText(this, "Configuração principal concluída.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Gama configurado. A escuta exige autorização do microfone.", Toast.LENGTH_LONG).show()
        }
        showStatus()

        if (AiSetup.isInstalled(this)) {
            maybeLaunchReadyIntro()
        } else {
            AlertDialog.Builder(this).setTitle("Instalar cérebro de conversa")
                .setMessage("Para concluir a configuração completa, o Gama precisa baixar seu modelo de linguagem (aproximadamente 555 MB) uma vez. Quando o modelo estiver instalado, a apresentação inicial acontecerá automaticamente. Comandos locais continuam funcionando durante esse processo.")
                .setPositiveButton("Instalar IA") { _, _ -> AiSetup.download(this) }
                .setNegativeButton("Depois", null).show()
        }
    }

    private fun introRequirementsReady(): Boolean =
        GamaIntroPolicy.shouldLaunch(
            setupDone = pref.getBoolean("setup_done", false),
            identityReady = OwnerIdentity.hasProfile(this) && OwnerIdentity.hasConfiguredTreatment(this),
            microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            modelInstalled = AiSetup.isInstalled(this),
            introComplete = pref.getBoolean(GamaIntroActivity.KEY_COMPLETE, false)
        )

    private fun maybeLaunchReadyIntro() {
        if (introLaunching || isFinishing || isDestroyed) return
        if (pref.getBoolean(GamaIntroActivity.KEY_COMPLETE, false)) return
        if (!introRequirementsReady()) return

        introLaunching = true
        try {
            startActivityForResult(
                Intent(this, GamaIntroActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                108
            )
        } catch (_: Exception) {
            introLaunching = false
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permita o microfone nos ajustes do Android.", Toast.LENGTH_LONG).show()
            return
        }
        // Prioridade desta versão: hotword confiável mesmo com a tela apagada.
        // O usuário ainda pode pausar a escuta a qualquer momento pela notificação.
        BackgroundMode.setKeepCpuAwake(this, true)
        try {
            startForegroundService(Intent(this, GamaService::class.java).setAction(BackgroundMode.ENABLE))
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Escuta ativada. Autorize a bolinha para vê-la e abrir apps por voz com mais confiabilidade.", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "O Android não permitiu iniciar a escuta. Abra Ajustes para tentar novamente.", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(intent, 106) }
        catch (_: Exception) { Toast.makeText(this, "Seletor de arquivos indisponível.", Toast.LENGTH_LONG).show() }
    }

    private fun settings() {
        val options = arrayOf("Ativar escuta", "Pausar escuta", "Editar identidade do proprietário",
            "Ativar bloqueio de tela", "Permissões do Android", "Permissões opcionais",
            "Apagar perfil e memória", "Sobre o Gama")
        AlertDialog.Builder(this).setTitle("Gama • ajustes e privacidade")
            .setItems(options) { _, choice -> when (choice) {
                0 -> { startListening(); showStatus() }
                1 -> { BackgroundMode.pause(this); showStatus() }
                2 -> { actionAfterCredential = "edit_owner"; requestCredential() }
                3 -> requestDeviceAdmin()
                4 -> openAppPermissions()
                5 -> optionalSettings()
                6 -> AlertDialog.Builder(this).setTitle("Apagar dados locais")
                    .setMessage("Apagar o perfil do proprietário, a memória explícita e as notas deste aparelho?")
                    .setPositiveButton("Confirmar no Android") { _, _ -> actionAfterCredential = "erase_profile"; requestCredential() }
                    .setNegativeButton("Cancelar", null).show()
                7 -> AlertDialog.Builder(this).setMessage(
                    "Gama 5.3 Criador público: ${GamaCreator.PUBLIC_NAME}. " +
                    "O proprietário é cadastrado separadamente em cada celular. " +
                    "O Gama não identifica com certeza quem está falando. Dados pessoais exigem confirmação pelo Android.")
                    .setPositiveButton("OK", null).show()
            } }
            .setPositiveButton("Fechar", null).show()
    }

    private fun optionalSettings() {
        val options = arrayOf("Autorizar calendário", "Autorizar contatos para WhatsApp", "Autorizar bolinha flutuante",
            "Ativar controle de tela e rolagem", "Autorizar acesso às notificações", "Não perturbe",
            "Definir Gama como assistente padrão", "Ativar IA offline", "Revisar economia de bateria",
            "Importar modelo manualmente (avançado)")
        AlertDialog.Builder(this).setTitle("Permissões opcionais")
            .setItems(options) { _, choice -> when (choice) {
                0 -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 202)
                1 -> requestContactsForWhatsApp()
                2 -> open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                3 -> if (!GamaScreenContextService.openSettings(this)) open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                4 -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                5 -> open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                6 -> open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                7 -> AiSetup.download(this)
                8 -> open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                9 -> selectModelFile()
            } }.setPositiveButton("Fechar", null).show()
    }


    private fun requestContactsForWhatsApp() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            AssistantRuntime.service?.completeWhatsAppContactsPermission(true)
            Toast.makeText(this, "O acesso aos contatos já está autorizado.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Contatos para WhatsApp")
            .setMessage("O Gama usa os contatos apenas para localizar pelo nome a pessoa para quem você pediu uma mensagem. O conteúdo continua sendo enviado pelo WhatsApp.")
            .setPositiveButton("Permitir") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 303)
            }
            .setNegativeButton("Agora não") { _, _ ->
                AssistantRuntime.service?.completeWhatsAppContactsPermission(false)
            }
            .show()
    }

    private fun showOwnerEditor() {
        val field = EditText(this).apply {
            setSingleLine(true)
            setText(OwnerIdentity.name(this@MainActivity).orEmpty())
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val senhor = RadioButton(this).apply { id = 4711; text = "Senhor" }
        val senhora = RadioButton(this).apply { id = 4712; text = "Senhora" }
        group.addView(senhor); group.addView(senhora)
        group.check(if (OwnerIdentity.treatment(this) == "Senhora") 4712 else 4711)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), 0)
            addView(TextView(this@MainActivity).apply { text = "Tratamento"; setTextColor(Color.WHITE) })
            addView(group)
            addView(TextView(this@MainActivity).apply { text = "Nome"; setTextColor(Color.WHITE) })
            addView(field)
        }
        AlertDialog.Builder(this).setTitle("Identidade do proprietário")
            .setView(holder).setPositiveButton("Salvar") { _, _ ->
                val treatment = if (group.checkedRadioButtonId == 4712) "Senhora" else "Senhor"
                if (OwnerIdentity.saveVerified(this, field.text.toString(), treatment)) {
                    GamaProfile.setTitle(this, treatment)
                    Toast.makeText(this, "Identidade atualizada: ${OwnerIdentity.address(this)}.", Toast.LENGTH_LONG).show()
                    showStatus()
                } else {
                    Toast.makeText(this, "Nome ou tratamento inválido.", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun deviceAdminComponent(): ComponentName =
        ComponentName(this, GamaDeviceAdminReceiver::class.java)

    private fun isDeviceAdminActive(): Boolean = try {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        dpm.isAdminActive(deviceAdminComponent())
    } catch (_: Exception) {
        false
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = deviceAdminComponent()
        if (dpm.isAdminActive(admin)) {
            Toast.makeText(this, "O bloqueio de tela por voz já está autorizado.", Toast.LENGTH_LONG).show()
            showStatus()
            return
        }

        val request = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Permita ao Gama bloquear a tela quando você pedir por voz. O Gama não recebe sua senha, PIN ou biometria."
            )
        }
        try {
            startActivity(request)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Não consegui abrir a autorização. Abra Segurança > Apps de administrador do dispositivo e ative o Gama.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openAppPermissions() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:$packageName")))
    private fun open(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "Opção indisponível neste aparelho.", Toast.LENGTH_LONG).show() }
    }
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
}
