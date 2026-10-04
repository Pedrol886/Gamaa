package com.gama.assistant

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import java.util.concurrent.ConcurrentLinkedQueue
import java.security.SecureRandom
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class GamaService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL = "gama_voice"
        const val NOTIF_ID = 42
        const val SAMPLE_RATE = 16000
        const val ACTION_CLOSE_UI = "com.gama.assistant.CLOSE_GAMA_UI"
        const val ACTION_ENROLL_OWNER = "com.gama.assistant.ENROLL_OWNER_VOICE"
        const val EXTRA_REQUEST_SECURE_UNLOCK = "request_secure_unlock"
        private const val TAG = "GamaService"

        // Speaker verification. This protects Gama commands, not Android's lock screen.
        private const val OWNER_PREFS = "muffin_owner_voice_v3" // legacy storage key: preserve enrolled owner data
        private const val OWNER_VECTOR_KEY = "owner_vector"
        private const val OWNER_FRAMES_KEY = "owner_frames"
        private const val OWNER_SAMPLES_KEY = "owner_samples"
        private const val OWNER_THRESHOLD_KEY = "owner_threshold"
        private const val OWNER_VOICE_ONLY = true
        private const val OWNER_ENROLL_SAMPLES = 7
        private const val OWNER_MIN_SPK_FRAMES = 45
        private const val OWNER_ENROLL_MIN_SPK_FRAMES = 75
        private const val OWNER_BASE_THRESHOLD = 0.68
        private const val OWNER_MIN_THRESHOLD = 0.62
        private const val OWNER_MAX_THRESHOLD = 0.79
        private const val OWNER_SAMPLE_MARGIN = 0.07
        private const val OWNER_ENROLL_MIN_COHESION = 0.44
        private const val OWNER_VARIATION_MARGIN = 0.12
        private const val OWNER_VARIATION_FLOOR = 0.54
        private const val OWNER_RETRY_ACCEPT_MARGIN = 0.07
        private const val OWNER_RETRY_MIN_SPK_FRAMES = 60
        private const val OWNER_RETRY_WINDOW_MS = 9000L
        private const val WAKE_WINDOW_MS = 9000L
        private const val COMMAND_START_TIMEOUT_MS = 2000L
        // Uma sessão permanece aberta enquanto houver conversa. Só volta ao hotword
        // depois de 20 minutos completos sem nenhuma fala do usuário.
        private const val CONVERSATION_IDLE_TIMEOUT_MS = 60L * 60L * 1000L
        private const val RESPONSE_WATCHDOG_MS = 5000L
        private const val OWNER_SESSION_MS = 30L * 60L * 1000L
        private const val CREATOR_RESEARCH_PHRASE =
            "acorda crianca preciso fazer uma pesquisa"
        private const val CREATOR_RESEARCH_WINDOW_MS = 45000L
    }

    private val pendingCommand = PendingCommand()
    @Volatile private var transcription: TranscriptionListener? = null
    @Volatile private var textMode = false
    @Volatile private var voiceReady = false
    @Volatile private var lastAudioReadAt = 0L
    @Volatile private var pendingActivity: Intent? = null
    private var deferredLaunch = false
    @Volatile private var requestGeneration = 0L
    private fun postAudio(action: () -> Unit) {
        if (textMode) uiHandler.post {
            if (textMode) {
                try { action() } catch (e: Exception) { Log.e(TAG, "Falha isolada em tarefa do Gama", e) }
            } else audioTasks.add(action)
        } else audioTasks.add(action)
    }
    @Volatile private var running = false
    @Volatile private var speaking = false
    @Volatile private var ttsReady = false
    @Volatile private var wakeUntil = 0L
    @Volatile private var conversationUntil = 0L
    @Volatile private var creatorResearchUntil = 0L
    @Volatile private var researchBusy = false
    @Volatile private var brainBusy = false
    @Volatile private var liveBusy = false
    @Volatile private var lastWakeAt = 0L
    @Volatile private var ttsSpeaking = false
    private var recognitionErrors = 0
    @Volatile private var closeUiAfterSpeech = false
    @Volatile private var waitingForCommandStart = false
    @Volatile private var commandSpeechStarted = false
    @Volatile private var armCommandWindowAfterTts = false
    @Volatile private var interactionToken = 0L
    @Volatile private var responsePending = false
    @Volatile private var responseWatchToken = 0L
    @Volatile private var conversationLastActivityAt = 0L
    @Volatile private var conversationIdleToken = 0L

    private val audioTasks = ConcurrentLinkedQueue<() -> Unit>()
    private val commandWindow = CommandWindow()
    private val conversation = ConversationSession()
    private val zevronPlanSteps = ConcurrentLinkedQueue<String>()
    @Volatile private var zevronPlanRunning = false
    @Volatile private var lastPlanStepMayContinue = true
    @Volatile private var currentPlanStep = ""
    private var bargePreRoll = ByteArray(0)
    private var headphoneCheckAt = 0L
    private var headphoneOutput = false
    private var listenerThread: Thread? = null
    private var ttsFailed = false
    @Volatile private var activeUtterance: String? = null
    @Volatile private var activeSpeechText: String? = null
    @Volatile private var ttsRetryCount = 0
    private var queuedWakeCommand: Pair<String, JSONObject>? = null
    private var challenge = ""
    private var challengeCommand = ""
    private var challengeFailures = 0
    private var challengeCooldownUntil = 0L
    private var recentAndroidAuth = 0L
    private var recentOwnerSession = 0L
    @Volatile private var unlockPending = false
    private var deferredSensitiveCommand: String? = null
    private var deferredSensitiveUntil = 0L
    private var ownerAuthCommand: String? = null
    private var ownerAuthUntil = 0L
    private var ownerAuthResumeConversation = false
    private var lastAdaptedAt = 0L
    private var cachedReferences: List<FloatArray>? = null
    private var noiseFloor = 45.0
    private val audioFrontEnd = GamaAudioFrontEnd(SAMPLE_RATE)
    @Volatile private var lastAudioMetrics = GamaAudioMetrics.initial()
    private var noisyAudioBlocks = 0
    private var lastNoiseHintAt = 0L
    private var voicedBlocks = 0
    private var preRoll = ByteArray(0)
    private var decoderActive = false
    private var lastSpeechAt = 0L
    private var utteranceStartedAt = 0L
    private var lastPartialAt = 0L
    private var ignoreAudioUntil = 0L
    private var enrollmentRequested = false
    private var enrollmentStartedAt = 0L
    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Never depend on the microphone worker to process screen/unlock events.
            // If AudioRecord is stalled during Android authentication, posting this
            // work to audioTasks would make Gama look permanently deaf afterwards.
            uiHandler.post {
                if (intent?.action == Intent.ACTION_USER_PRESENT && !isDeviceLocked()) {
                    unlockPending = false
                    recentAndroidAuth = SystemClock.elapsedRealtime()
                    resumeAfterUnlock()
                    val deferredAutomation = pendingAutomationAfterUnlock
                    pendingAutomationAfterUnlock = null
                    if (deferredAutomation != null) {
                        uiHandler.postDelayed({ postAudio { executeAutomationRule(deferredAutomation) } }, 450L)
                    }
                } else if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    recentOwnerSession = 0L
                    recentAndroidAuth = 0L
                    hideWakeUi()
                }
            }
        }
    }

    private val brainProcessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val requestId = intent?.getLongExtra(BrainProcessProtocol.EXTRA_REQUEST_ID, 0L) ?: return
            if (requestId <= 0L || requestId != activeBrainRequestId) return

            when (intent.action) {
                BrainProcessProtocol.ACTION_STARTED -> {
                    activeBrainPid = intent.getIntExtra(BrainProcessProtocol.EXTRA_PROCESS_PID, 0)
                    scheduleBrainProcessHealthCheck(requestId)
                }

                BrainProcessProtocol.ACTION_RESULT -> {
                    val answer = intent.getStringExtra(BrainProcessProtocol.EXTRA_ANSWER).orEmpty()
                    val success = intent.getBooleanExtra(BrainProcessProtocol.EXTRA_SUCCESS, false)
                    finishIsolatedBrainResult(requestId, answer, success)
                }
            }
        }
    }

    private var pendingSpeech: String? = null
    private var pendingCloseAfterSpeech = false
    private var queuedSpeechText: String? = null
    private var queuedSpeechCloseAfter = false
    private var queuedSpeechRecord = true
    private var pendingLockAfterSpeech = false
    private var pendingSuggestedCommand: String? = null
    private var pendingSuggestedAt = 0L
    private val automationEngine by lazy { GamaAutomationEngine(applicationContext) }
    @Volatile private var pendingAutomationDraft: AutomationDraft? = null
    @Volatile private var pendingAutomationUntil = 0L
    @Volatile private var automationTickToken = 0L
    @Volatile private var automationExecuting = false
    private var pendingAutomationAfterUnlock: GamaAutomationEngine.Rule? = null
    private var pendingWhatsAppTarget: String? = null
    private var pendingWhatsAppAt = 0L
    @Volatile private var installedAppsCache: List<InstalledApp>? = null
    private var installedAppsCacheAt = 0L
    private var pendingWhatsAppPermission: WhatsAppSendRequest? = null
    private var pendingWhatsAppAccessibility: WhatsAppSendRequest? = null
    @Volatile private var pendingNotificationReadAt = 0L
    @Volatile private var pendingLockAfterAccessibility = false
    private var audioRecord: AudioRecord? = null
    private var model: Model? = null
    private var speakerModel: SpeakerModel? = null
    private var recognizer: Recognizer? = null
    // Detector dedicado da hotword. Enquanto o Gama está em espera ele usa um
    // vocabulário minúsculo e recebe o áudio continuamente, sem depender do VAD
    // do reconhecedor de frases. Isso deixa “Gama” muito mais confiável em tela
    // apagada, fala baixa e ruído ambiente.
    private var wakeRecognizer: Recognizer? = null
    private var wakePreRoll = ByteArray(0)
    private var lastDedicatedWakePartialAt = 0L
    private var wakePartialCandidate = ""
    private var wakePartialHits = 0
    private var wakePartialFirstAt = 0L
    // Detector separado de encerramento. Durante uma conversa ele escuta apenas
    // frases curtas de descanso/saída, para que ruído ou baixa confiança no
    // reconhecedor principal nunca prendam o Gama no ciclo de "não entendi".
    private var sleepRecognizer: Recognizer? = null
    private var lastDedicatedSleepPartialAt = 0L
    // Ao trocar para outro app, alguns aparelhos emitem sons curtos que o Vosk
    // pode interpretar como fala. Mantemos duas janelas pequenas: uma para
    // baixa confiança e outra, um pouco maior, apenas para falsos números.
    private var postExternalNoiseGuardUntil = 0L
    private var postExternalNumericGuardUntil = 0L
    private var externalActionGuardAfterTts = false
    @Volatile private var externalLaunchStatusUntil = 0L
    @Volatile private var partialWakeToken = 0L
    @Volatile private var ownerEnrollmentMode = false
    private val ownerEnrollmentVectors = mutableListOf<FloatArray>()
    @Volatile private var ownerRetryPending = false
    private var ownerRetryFirstScore = -1.0
    private var ownerRetryThreshold = OWNER_BASE_THRESHOLD
    private var ownerRetryUntil = 0L

    private enum class OwnerVoiceDecision { MATCH, RETRY, REJECT }

    private data class OwnerVoiceAssessment(
        val decision: OwnerVoiceDecision,
        val score: Double,
        val threshold: Double
    )
    private var tts: TextToSpeech? = null
    private var orb: GamaEnergyView? = null
    private var wm: WindowManager? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var lastPartial = ""
    // Most recent meaningful partial from Vosk. Final results can occasionally
    // become empty/short at an endpoint even though the partial was correct.
    // Keep a very short-lived copy so Gama can recover the utterance instead of
    // asking the user to repeat it.
    private var lastUsefulPartial = ""
    private var lastUsefulPartialAt = 0L
    private var lastVoiceEvidenceAt = 0L
    private var lastWakePulse = 0L
    @Volatile private var brainRequestSerial = 0L
    @Volatile private var activeBrainRequestId = 0L
    @Volatile private var activeBrainGeneration = 0L
    @Volatile private var activeBrainPid = 0
    @Volatile private var brainHealthToken = 0L
    @Volatile private var brainCooldownUntil = 0L
    @Volatile private var pendingCalendarTitleUntil = 0L
    private var listenerWakeLock: PowerManager.WakeLock? = null
    private var speechHoldUntil = 0L
    private var lastFastTimeAt = 0L
    private val proactiveEvents = ConcurrentLinkedQueue<GamaEventHub.Event>()
    @Volatile private var lastProactiveSpokenAt = 0L
    @Volatile private var proactiveDrainToken = 0L
    @Volatile private var lastCalendarAwarenessCheck = 0L

    private val uiHandler = android.os.Handler(Looper.getMainLooper())
    private val uiTimeout = Runnable {
        if (!conversation.active) {
            wakeUntil = 0L
            conversationUntil = 0L
            hideWakeUi()
        }
    }

    override fun onCreate() {
        // GAMA91_AUDIO_DSP_START
        GamaAudioNoiseSupervisor.start(this)

        // GAMA67_STABILITY_SUPERVISORS
        brainProcessSupervisor = BrainProcessSupervisor(this) {
            uiHandler.post { handleBrainProcessDeath() }
        }
        RuntimeRecoveryBus.listener = { threadName, errorClass ->
            uiHandler.post { handleRecoverableWorkerFailure(threadName, errorClass) }
        }
        GamaProactiveBridge.listener = { event ->
            uiHandler.post { handleProactiveEvent(event) }
        }
        uiHandler.postDelayed(listenerSupervisorRunnable, 4_000L)

        super.onCreate()
        AssistantRuntime.service = this
        // O perfil de voz, quando cadastrado, é apenas foco em ambiente ruidoso.
        // Autenticação privada continua exclusivamente sob controle do Android.
        createChannel()
        textMode = true
        val stateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(deviceStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(deviceStateReceiver, stateFilter)

        val brainFilter = IntentFilter().apply {
            addAction(BrainProcessProtocol.ACTION_STARTED)
            addAction(BrainProcessProtocol.ACTION_RESULT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(brainProcessReceiver, brainFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(brainProcessReceiver, brainFilter)
        }

        if (!isDeviceLocked()) {
            recentAndroidAuth = SystemClock.elapsedRealtime() // trusted unlocked service start
        }
        // Usa o mecanismo TTS padrão do aparelho para evitar falhas de inicialização
        // de engines específicas. A seleção de voz masculina continua sendo tentada
        // depois, via lista de vozes exposta pelo próprio Android.
        running = true
        tts = try {
            TextToSpeech(this, this)
        } catch (e: Exception) {
            ttsFailed = true
            Log.e(TAG, "Falha ao inicializar TTS", e)
            null
        }

        uiHandler.postDelayed({
            if (running && !ttsReady && !ttsFailed) {
                ttsFailed = true
                updateNotification("Voz indisponível. Verifique o TTS nas configurações do Android.")
                val id = "tts-init-timeout"
                activeUtterance = id
                finishTts(id)
            }
        }, 8000L)

        // Não mostramos a bolinha ao iniciar.
        // A palavra de ativação agora é fixa: "Gama".
        running = true

        AssistantRuntime.state("Pronto • modo texto")
        scheduleAutomationTick()
    }

    private fun drainAudioTasks() {
    while (true) {
        val action = audioTasks.poll() ?: break
        try { action() } catch (e: Exception) { Log.w(TAG, "Falha em tarefa de áudio", e) }
    }
}

private fun waitForMicRetry(delayMs: Long) {
    val deadline = SystemClock.elapsedRealtime() + delayMs
    while (running && SystemClock.elapsedRealtime() < deadline) {
        drainAudioTasks()
        try { Thread.sleep(200L) } catch (_: InterruptedException) { return }
    }
}

    private fun enableVoice() {
        // A dead/stalled listener must be restartable even when textMode was already false.
        if (!textMode && running && listenerThread?.isAlive == true && voiceReady) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            BackgroundMode.setEnabled(this, false)
            AssistantRuntime.state("Permita o microfone para ativar a voz")
            pauseVoiceWithoutStoppingService()
            return
        }
        try { startForeground(NOTIF_ID, notification("Preparando reconhecimento de voz...")) }
        catch (_: Exception) { BackgroundMode.setEnabled(this, false); pauseVoiceWithoutStoppingService(); AssistantRuntime.state("Abra o Gama para ativar o microfone"); return }
        running = true
        textMode = false
        BackgroundMode.setEnabled(this, true)
        AssistantRuntime.state("Preparando escuta em segundo plano…")
        if (BackgroundMode.keepCpuAwake(this)) acquireListenerWakeLock()
        listenerThread = thread(name = "gama-listener") {
            var consecutiveFailures = 0
            try {
                while (running) {
                    try {
                        listenLoop()
                        consecutiveFailures = 0
                    } catch (_: SecurityException) {
                        BackgroundMode.setEnabled(this@GamaService, false)
                        updateNotification("Permita o microfone no aplicativo Gama.")
                        running = false
                        pauseVoiceWithoutStoppingService()
                    } catch (e: LinkageError) {
                        BackgroundMode.setEnabled(this@GamaService, false)
                        updateNotification("Motor de voz incompatível com este aparelho.")
                        running = false
                        pauseVoiceWithoutStoppingService()
                    } catch (e: Exception) {
                        if (running) {
                            consecutiveFailures++
                            Log.e(TAG, "Falha no microfone: ${e.javaClass.simpleName}", e)
                            val retryDelay = minOf(10000L, 1500L * consecutiveFailures.coerceAtMost(6))
                        updateNotification("Reconectando o microfone...")
                        waitForMicRetry(retryDelay)
                        if (consecutiveFailures > 6) consecutiveFailures = 3
                        }
                    }
                }
            } finally {
                releaseListenerWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.gama.assistant.TEXT" -> intent.getStringExtra("text")?.let { submitText(it) }
            "com.gama.assistant.LISTEN" -> { enableVoice(); if (!textMode) postAudio { acknowledgeWake() } }
            "com.gama.assistant.END_CONVERSATION" -> endConversation()
            "com.gama.assistant.INTERRUPT" -> interruptSpeechForUser()
            ACTION_ENROLL_OWNER -> {
                enrollmentRequested = true
                enableVoice()
                postAudio { startEnrollmentWhenReady() }
            }
            BackgroundMode.ENABLE -> enableVoice()
            null -> if (BackgroundMode.enabled(this)) enableVoice() else pauseVoiceWithoutStoppingService()
            "com.gama.assistant.STOP" -> { BackgroundMode.setEnabled(this, false); pauseVoiceWithoutStoppingService(); return START_NOT_STICKY }
        }
        return if (textMode) START_NOT_STICKY else START_STICKY
    }

    fun startTranscription(listener: TranscriptionListener) { postAudio {
        if (!running || textMode || transcription != null || ttsSpeaking || speaking || waitingForCommandStart || commandSpeechStarted || brainBusy || researchBusy) {
            listener.error(android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return@postAudio
        }
        recognizer?.reset()
        decoderActive = false
        preRoll = ByteArray(0)
        lastPartial = ""
        transcription = listener
        commandWindow.arm(SystemClock.elapsedRealtime(), 5000L)
        waitingForCommandStart = true
        commandSpeechStarted = false
        listener.ready()
        uiHandler.postDelayed({
            postAudio {
                if (transcription === listener) {
                    transcription = null
                    recognizer?.reset()
                    decoderActive = false
                    finishCommandListening()
                    listener.error(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
            }
        }, 60000L)
    } }
    fun stopTranscription(listener: TranscriptionListener, cancel: Boolean) { postAudio {
        if (transcription !== listener) return@postAudio
        transcription = null
        val result = if (cancel) "" else try { JSONObject(recognizer?.finalResult ?: "{}").optString("text") } catch (_: Exception) { "" }
        recognizer?.reset()
        decoderActive = false
        finishCommandListening()
        if (!cancel) listener.result(result)
    } }

    fun isVoiceActive(): Boolean = running && !textMode
    fun isVoiceReady(): Boolean = isVoiceActive() && voiceReady

    /** Keeps the hotword engine alive but prevents the intro TTS from feeding back into Vosk. */
    fun beginIntroPresentation() {
        postAudio {
            ignoreAudioUntil = Long.MAX_VALUE / 4L
            transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
            transcription = null
            recognizer?.reset()
            decoderActive = false
            preRoll = ByteArray(0)
            bargePreRoll = ByteArray(0)
            finishCommandListening()
        }
    }

    /** Restores normal hotword decoding shortly after the ceremony voice finishes. */
    fun endIntroPresentation() {
        postAudio {
            ignoreAudioUntil = SystemClock.elapsedRealtime() + 1200L
            recognizer?.reset()
            decoderActive = false
            preRoll = ByteArray(0)
            bargePreRoll = ByteArray(0)
            if (running) updateNotification("Ativo em segundo plano • diga Gama")
        }
    }
    fun submitText(text: String) { postAudio {
        val input = text.take(1500).trim()
        when (ZevronConversationEngine.control(input)) {
            ZevronConversationEngine.Control.WAKE_ONLY -> acknowledgeWake()
            ZevronConversationEngine.Control.SLEEP -> closeConversationForGoodbye()
            ZevronConversationEngine.Control.NORMAL -> {
                val command = ZevronConversationEngine.stripWakeWord(input)
                if (command.isNotBlank()) authorizeCommand(command, JSONObject())
            }
        }
    } }
    fun resumeAfterUnlock() {
        if (isDeviceLocked()) return
        unlockPending = false
        pendingActivity = null
        recentAndroidAuth = SystemClock.elapsedRealtime()
        val deferred = pendingCommand.take(false, SystemClock.elapsedRealtime())
        ensureVoicePipelineAfterUnlock()
        if (!deferred.isNullOrBlank()) {
            uiHandler.postDelayed({ postAudio {
                if (!isDeviceLocked()) executeAndReply(deferred)
            } }, 550L)
        } else if (conversation.active) {
            uiHandler.postDelayed({ postAudio {
                if (!isDeviceLocked()) {
                    touchConversation()
                    beginCommandWindow()
                    updateNotification("Conversa contínua • pode falar")
                }
            } }, 450L)
        } else {
            updateNotification("Ativo em segundo plano • diga Gama")
        }
    }

    private fun ensureVoicePipelineAfterUnlock() {
        val now = SystemClock.elapsedRealtime()
        val stale = listenerThread?.isAlive != true || !voiceReady ||
            (lastAudioReadAt > 0L && now - lastAudioReadAt > 3_000L)
        if (stale) {
            recoverMicrophoneOnly("unlock")
        } else {
            postAudio {
                recognizer?.reset()
                decoderActive = false
                voicedBlocks = 0
                preRoll = ByteArray(0)
                bargePreRoll = ByteArray(0)
                speechHoldUntil = 0L
                ignoreAudioUntil = SystemClock.elapsedRealtime() + 350L
            }
        }
        uiHandler.postDelayed({
            val checkNow = SystemClock.elapsedRealtime()
            val stillStale = listenerThread?.isAlive != true || !voiceReady ||
                (lastAudioReadAt > 0L && checkNow - lastAudioReadAt > 3_500L)
            if (!textMode && BackgroundMode.enabled(this) && stillStale) {
                recoverMicrophoneOnly("unlock-watchdog")
            }
        }, 1_800L)
    }
    fun cancelRequest() { postAudio {
        requestGeneration++
        transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
        transcription = null
        pendingCommand.clear()
        ownerAuthCommand = null
        ownerAuthUntil = 0L
        ownerAuthResumeConversation = false
        queuedWakeCommand = null
        armCommandWindowAfterTts = false
        finishCommandListening()
        clearOwnerVoiceRetry()
        unlockPending = false
        pendingActivity = null
        responsePending = false
        uiHandler.post { tts?.stop(); speak("Cancelado.", !conversation.active) }
    } }
    fun launchSafely(intent: Intent): String {
        // Em Android recente, abrir uma Activity a partir do segundo plano pode
        // ser bloqueado. Mantemos a bolinha visível durante o start quando a
        // permissão de overlay existe, ou usamos o VoiceInteractionService se
        // o Gama for o assistente padrão.
        externalActionGuardAfterTts = true
        val now = SystemClock.elapsedRealtime()
        postExternalNoiseGuardUntil = maxOf(postExternalNoiseGuardUntil, now + 3500L)
        postExternalNumericGuardUntil = maxOf(postExternalNumericGuardUntil, now + 12000L)

        val target = Intent(intent).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        pendingActivity = target

        val foregroundNow = GamaApplication.foreground
        val assistantNow = SystemAssistant.current
        val overlayGranted = Settings.canDrawOverlays(this)
        val route = when {
            foregroundNow -> BackgroundLaunchPolicy.Route.ACTIVITY
            assistantNow != null -> BackgroundLaunchPolicy.Route.ASSISTANT
            overlayGranted -> BackgroundLaunchPolicy.Route.ACTIVITY
            else -> BackgroundLaunchPolicy.Route.NOTIFICATION
        }

        val canOpen = route != BackgroundLaunchPolicy.Route.NOTIFICATION
        deferredLaunch = !canOpen
        externalLaunchStatusUntil = now + 3200L

        uiHandler.post {
            if (!running) return@post

            fun fallbackToNotification() {
                deferredLaunch = true
                pendingActivity = target
                updateNotification("Toque em Continuar pedido na notificação do Gama.")
            }

            try {
                when (route) {
                    BackgroundLaunchPolicy.Route.ASSISTANT -> {
                        updateNotification("Abrindo aplicativo...")
                        val assistant = assistantNow
                        if (assistant != null) assistant.present(target) else fallbackToNotification()
                    }
                    BackgroundLaunchPolicy.Route.ACTIVITY -> {
                        updateNotification("Abrindo aplicativo...")
                        if (!foregroundNow && overlayGranted) {
                            // A sobreposição precisa estar anexada no momento do
                            // startActivity em alguns aparelhos Samsung.
                            showOrb()
                            uiHandler.postDelayed({
                                if (!running) return@postDelayed
                                try { super.startActivity(target) }
                                catch (_: Exception) { fallbackToNotification() }
                            }, 180L)
                        } else {
                            super.startActivity(target)
                        }
                    }
                    BackgroundLaunchPolicy.Route.NOTIFICATION -> fallbackToNotification()
                }
            } catch (_: Exception) {
                fallbackToNotification()
            }
        }

        return if (canOpen) "Abrindo."
        else "Para abrir aplicativos só por voz, autorize a bolinha flutuante ou defina o Gama como assistente padrão. O pedido também está na notificação."
    }

    override fun startActivity(intent: Intent) { launchSafely(intent) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // GAMA67_STABILITY_SUPERVISORS_STOP
        uiHandler.removeCallbacks(listenerSupervisorRunnable)
        RuntimeRecoveryBus.listener = null
        GamaProactiveBridge.listener = null
        proactiveDrainToken++
        proactiveEvents.clear()
        runCatching { brainProcessSupervisor?.close() }
        brainProcessSupervisor = null

        running = false
        voiceReady = false
        automationTickToken++
        pendingAutomationDraft = null
        pendingAutomationAfterUnlock = null
        conversation.close()
        GamaSupervisor.onConversationClosed()
        transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
        transcription = null
        requestGeneration++
        pendingCommand.clear()
        ownerAuthCommand = null
        ownerAuthUntil = 0L
        ownerAuthResumeConversation = false
        if (AssistantRuntime.service === this) AssistantRuntime.service = null
        AssistantRuntime.state("Voz pausada")
        interactionToken++
        responseWatchToken++
        audioTasks.clear()
        uiHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(deviceStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(brainProcessReceiver) } catch (_: Exception) {}
        brainHealthToken++
        activeBrainRequestId = 0L
        activeBrainPid = 0
        try { stopService(Intent(this, GamaBrainProcessService::class.java)) } catch (_: Exception) {}
        // Stop unblocks read; only the audio worker may close native objects.
        try { audioRecord?.stop() } catch (_: Exception) {}
        listenerThread?.interrupt()
        activeUtterance = null
        tts?.stop()
        tts?.shutdown()
        ttsReady = false
        releaseListenerWakeLock()
        GamaAudioNoiseSupervisor.stop()
        removeOrb()
        sendBroadcast(Intent(ACTION_CLOSE_UI).setPackage(packageName))
        // The local model is intentionally hosted in :gama_brain, never in this process.
        super.onDestroy()
    }

    private fun handleProactiveEvent(event: GamaEventHub.Event) {
        if (!running || event.priority != GamaEventHub.Priority.HIGH) return

        val age = System.currentTimeMillis() - event.occurredAt
        if (!GamaSupervisor.maySpeakProactively(conversation.active, age)) {
            if (!conversation.active) {
                updateNotification("Atividade importante registrada • diga Gama para conversar")
                proactiveEvents.clear()
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val busy = brainBusy || liveBusy || researchBusy || automationExecuting ||
            commandSpeechStarted || waitingForCommandStart || transcription != null
        val elapsed = if (lastProactiveSpokenAt == 0L) Long.MAX_VALUE else now - lastProactiveSpokenAt

        if (!GamaProactivePolicy.shouldSpeakNow(
                event.priority,
                speaking || ttsSpeaking,
                busy,
                elapsed,
            )) {
            if (proactiveEvents.size < 5) proactiveEvents.add(event)
            scheduleProactiveDrain(
                if (elapsed < 45_000L) (45_000L - elapsed).coerceAtLeast(2_000L) else 3_000L
            )
            return
        }

        lastProactiveSpokenAt = now
        val reply = if (event.kind == GamaEventHub.Kind.MESSAGE) {
            ZevronContextResolver.rememberExternalRecipient(event.source, event.summary)
            GamaProactivePolicy.spokenSummary(
                event.source,
                event.summary,
                isDeviceLocked(),
                repeated = false,
            )
        } else {
            when (event.source) {
                "Agenda" -> if (isDeviceLocked()) {
                    "Senhor, você tem um compromisso importante em breve."
                } else {
                    "Senhor, ${event.summary}."
                }
                "Bateria" -> "Senhor, ${event.summary}"
                else -> "Senhor, ${event.summary}"
            }
        }

        updateNotification(reply)
        speak(reply, closeAfter = false, record = !isDeviceLocked())
    }

    private fun scheduleProactiveDrain(delayMs: Long = 3_000L) {
        val token = ++proactiveDrainToken
        uiHandler.postDelayed({
            if (!running || token != proactiveDrainToken) return@postDelayed
            drainProactiveQueue()
        }, delayMs)
    }

    private fun drainProactiveQueue() {
        if (!running) return
        if (!GamaSupervisor.shouldDrainProactiveQueue(conversation.active)) {
            proactiveEvents.clear()
            return
        }
        val event = proactiveEvents.poll() ?: return
        val now = SystemClock.elapsedRealtime()
        val busy = speaking || ttsSpeaking || brainBusy || liveBusy || researchBusy || automationExecuting ||
            commandSpeechStarted || waitingForCommandStart || transcription != null
        val elapsed = if (lastProactiveSpokenAt == 0L) Long.MAX_VALUE else now - lastProactiveSpokenAt
        if (!GamaProactivePolicy.shouldSpeakNow(event.priority, speaking || ttsSpeaking, busy, elapsed)) {
            proactiveEvents.add(event)
            scheduleProactiveDrain(if (elapsed < 45_000L) (45_000L - elapsed).coerceAtLeast(2_000L) else 3_000L)
            return
        }
        handleProactiveEvent(event)
    }

    private fun releaseListenerWakeLock() {
        try { if (listenerWakeLock?.isHeld == true) listenerWakeLock?.release() } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (!running) return
        if (status == TextToSpeech.SUCCESS) {
            ttsFailed = false
            ttsReady = true
            postAudio { startEnrollmentWhenReady() }

            // Respeita a voz escolhida pelo usuário no Android/Samsung.
            // Só define pt-BR se o mecanismo iniciou em outro idioma.
            try {
                val currentLanguage =
                    tts?.voice?.locale?.language

                if (!currentLanguage.equals("pt", true)) {
                    tts?.language = Locale("pt", "BR")
                }
            } catch (_: Exception) {}

            // Sem alterar o gênero da voz com pitch artificial.
            GamaVoice.apply(this, tts)

            // Não pedimos audio focus. A fala do Gama é misturada ao áudio
            // de mídia, em vez de pausar ou silenciar vídeos.
            try {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (_: Exception) {}

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != activeUtterance || !running) return
                    speaking = true
                    ttsSpeaking = true
                    animateOrbSpeaking()
                }
                override fun onDone(utteranceId: String?) = finishTts(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = retryOrFinishTts(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (interrupted) retryOrFinishTts(utteranceId) else finishTts(utteranceId)
                }
            })

            pendingSpeech?.let { text ->
                val closeAfter = pendingCloseAfterSpeech
                pendingSpeech = null
                pendingCloseAfterSpeech = false
                speak(text, closeAfter, false)
            }
        } else {
            ttsFailed = true
            updateNotification("TTS indisponível. O reconhecimento continua ativo.")
            val id = "tts-init-error"
            activeUtterance = id
            finishTts(id)
        }
    }

    fun refreshVoice() { uiHandler.post { if (running && ttsReady) GamaVoice.apply(this, tts) } }

    private fun restartTtsAfterFailure() {
        if (!running || ttsReady) return
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsFailed = false
        uiHandler.postDelayed({
            if (!running || ttsReady) return@postDelayed
            tts = try {
                TextToSpeech(this, this)
            } catch (e: Exception) {
                ttsFailed = true
                Log.e(TAG, "Falha ao reiniciar TTS", e)
                null
            }
        }, 350L)
    }

    private fun safeTtsSpeak(text: String, id: String): Int {
        CrashRecorder.markComponent("TTS")

        val engine = tts ?: return TextToSpeech.ERROR
        return try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) {
            Log.e(TAG, "Mecanismo TTS falhou durante a resposta; recuperando", e)
            ttsReady = false
            speaking = false
            ttsSpeaking = false
            updateNotification("Recuperando a voz do Gama...")
            restartTtsAfterFailure()
            TextToSpeech.ERROR
        }
    }

    private fun retryOrFinishTts(id: String?) {
        uiHandler.post {
            if (id != activeUtterance || !running) return@post
            val text = activeSpeechText
            if (text.isNullOrBlank() || ttsRetryCount >= 1 || !ttsReady) {
                finishTts(id)
                return@post
            }
            ttsRetryCount++
            val retryId = "gama-retry-${SystemClock.elapsedRealtimeNanos()}"
            activeUtterance = retryId
            speaking = true
            ttsSpeaking = true
            val result = safeTtsSpeak(text, retryId)
            if (result == null || result == TextToSpeech.ERROR) {
                finishTts(retryId)
            } else {
                uiHandler.postDelayed(
                    { if (activeUtterance == retryId) finishTts(retryId) },
                    maxOf(15000L, text.length * 180L)
                )
            }
        }
    }

    private fun finishTts(id: String?) {
    uiHandler.post {
        if (id != activeUtterance || !running) return@post
        activeUtterance = null
        activeSpeechText = null
        ttsRetryCount = 0
        speaking = false
        ttsSpeaking = false

        if (pendingLockAfterSpeech) {
            pendingLockAfterSpeech = false
            queuedSpeechText = null
            queuedSpeechCloseAfter = false
            queuedSpeechRecord = true
            armCommandWindowAfterTts = false
            closeUiAfterSpeech = false
            hideWakeUi()
            uiHandler.postDelayed({
                performDeviceLockNow()
                continuePlanAfterToolAction(360L)
            }, 220L)
            return@post
        }

        val nextText = queuedSpeechText
        val nextClose = queuedSpeechCloseAfter
        val nextRecord = queuedSpeechRecord
        queuedSpeechText = null
        queuedSpeechCloseAfter = false
        queuedSpeechRecord = true

        val now = SystemClock.elapsedRealtime()
        ignoreAudioUntil = now + 650L
        if (externalActionGuardAfterTts) {
            externalActionGuardAfterTts = false
            postExternalNoiseGuardUntil = now + 2400L
            postExternalNumericGuardUntil = now + 8000L
        }
        postAudio {
            recognizer?.reset()
            decoderActive = false
            voicedBlocks = 0
            preRoll = ByteArray(0)
            bargePreRoll = ByteArray(0)
            speechHoldUntil = 0L
        }

        if (!nextText.isNullOrBlank()) {
            speak(nextText, nextClose, nextRecord)
            return@post
        }

        // Continue an explicit multi-step plan only after the current tool/reply
        // has really finished. Barge-in still cancels the queue in executeAndReply().
        if (continuePlanAfterToolAction(160L)) return@post

        if (GamaSupervisor.shouldDrainProactiveQueue(conversation.active) &&
            proactiveEvents.isNotEmpty()) {
            scheduleProactiveDrain(1_200L)
        } else if (!conversation.active) {
            proactiveEvents.clear()
        }

        setConversationVisual(GamaEnergyView.Mode.LISTENING)
        postAudio {
            if (armCommandWindowAfterTts) {
                beginCommandWindow()
                val queued = queuedWakeCommand
                queuedWakeCommand = null
                if (queued != null) authorizeCommand(queued.first, queued.second, fromVoice = true)
            } else if (conversation.active && transcription == null && !ownerEnrollmentMode) {
                touchConversation()
                beginCommandWindow()
                updateNotification("Conversa contínua • pode falar")
            } else if (closeUiAfterSpeech) {
                closeUiAfterSpeech = false
                hideWakeUi()
            }
        }
    }
}

    private fun continuePlanAfterToolAction(delayMs: Long): Boolean {
        if (!zevronPlanRunning) return false

        // Never let a failed Android/tool step silently cascade into the rest of a plan.
        // The failure reply has already been spoken, so the safest behavior is to pause.
        if (!lastPlanStepMayContinue) {
            if (zevronPlanSteps.isNotEmpty()) GamaMissionStore.pause(this, zevronPlanSteps.toList())
            zevronPlanRunning = false
            currentPlanStep = ""
            updateNotification("Sequência pausada • etapa anterior não confirmada")
            return false
        }

        val nextPlanStep = zevronPlanSteps.poll()
        if (!nextPlanStep.isNullOrBlank()) {
            currentPlanStep = nextPlanStep
            lastPlanStepMayContinue = true
            GamaMissionStore.advance(this, nextPlanStep, zevronPlanSteps.toList())
            uiHandler.postDelayed({
                if (running && zevronPlanRunning) {
                    executeAndReply(nextPlanStep, fromPlan = true)
                }
            }, delayMs)
            return true
        }
        zevronPlanRunning = false
        currentPlanStep = ""
        lastPlanStepMayContinue = true
        GamaMissionStore.complete(this)
        return false
    }

    private fun listenLoop() {
        CrashRecorder.markComponent("microphone")

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microfone não autorizado")
        }
        try {
            model = Model(ensureModel().absolutePath)
            // Speaker vectors are used only to focus on the enrolled owner in a crowd.
            // Android credentials remain mandatory for private/sensitive actions.
            speakerModel = try {
                SpeakerModel(ensureSpeakerModel().absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Modelo de locutor indisponível; mantendo reconhecimento de fala normal", e)
                null
            }
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer?.setWords(true)
            speakerModel?.let { runCatching { recognizer?.setSpeakerModel(it) } }
            wakeRecognizer = try {
                Recognizer(model, SAMPLE_RATE.toFloat(), "[\"gama\", \"gamma\", \"[unk]\"]").also { wake ->
                    speakerModel?.let { runCatching { wake.setSpeakerModel(it) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Detector dedicado de hotword indisponível; usando reconhecedor principal", e)
                null
            }
            sleepRecognizer = try {
                Recognizer(
                    model, SAMPLE_RATE.toFloat(),
                    "[\"pode descansar\", \"pode descansa\", \"voce pode descansar\", \"pode descansar por agora\", \"pode ir descansar\", \"pare de ouvir\", \"pode parar de ouvir\", \"encerrar conversa\", \"tchau\", \"ate mais\", \"[unk]\"]"
                ).also { sleep ->
                    speakerModel?.let { runCatching { sleep.setSpeakerModel(it) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Detector dedicado de descanso indisponível; usando reconhecedor principal", e)
                null
            }
            audioFrontEnd.reset()
            lastAudioMetrics = GamaAudioMetrics.initial()
            noisyAudioBlocks = 0
            wakePreRoll = ByteArray(0)
            lastDedicatedWakePartialAt = 0L
            wakePartialCandidate = ""
            wakePartialHits = 0
            wakePartialFirstAt = 0L
            lastDedicatedSleepPartialAt = 0L
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Formato de microfone indisponível" }
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 8192))
            audioRecord = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microfone indisponível" }
            if (Build.VERSION.SDK_INT >= 29) {
                // Ask devices with multi-mic/beam-forming support to favor the
                // person facing the phone. Unsupported devices simply ignore it.
                runCatching {
                    recorder.setPreferredMicrophoneDirection(AudioRecord.MIC_DIRECTION_TOWARDS_USER)
                    recorder.setPreferredMicrophoneFieldDimension(0.65f)
                }
            }
            if (!running) return
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            GamaAudioNoiseSupervisor.attachNow(recorder)
            voiceReady = true
            startEnrollmentWhenReady()
            AssistantRuntime.state("Escuta ativa • diga Gama")
            updateNotification("Ativo em segundo plano • diga Gama")
            val buffer = ByteArray(1024) // 32 ms blocks: lower wake/command latency without polling spin.
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (!running) break
                if (read < 0) error("Microfone interrompido: $read")
                if (read == 0) { drainAudioTasks(); Thread.sleep(20L); continue }
                val audioMetrics = audioFrontEnd.processInPlace(buffer, read)
                lastAudioMetrics = audioMetrics
                // Keep the legacy barge-in gate synchronized with the real environment.
                noiseFloor = (noiseFloor * 0.88 + audioMetrics.noiseRms * 0.12).coerceIn(35.0, 4200.0)
                if (audioMetrics.severeNoise) {
                    noisyAudioBlocks++
                } else {
                    noisyAudioBlocks = (noisyAudioBlocks - 1).coerceAtLeast(0)
                }
                lastAudioReadAt = SystemClock.elapsedRealtime()
                drainAudioTasks()
                // Cadastro vocal anterior desativado nesta versão.
                val rec = recognizer ?: break
                val now = SystemClock.elapsedRealtime()
                if (ownerEnrollmentMode && (now - enrollmentStartedAt > 180000L || isDeviceLocked())) {
                    ownerEnrollmentMode = false
                    ownerEnrollmentVectors.clear()
                    updateNotification("Abra o Gama para continuar.")
                }
                if (commandWindow.expired(now) && !conversation.active) {
                    transcription?.error(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    transcription = null
                    finishCommandListening()
                    clearOwnerVoiceRetry()
                    rec.reset()
                    decoderActive = false
                    updateNotification("Gama está aguardando: diga Gama.")
                    hideWakeUi()
                }
                if (speaking || ttsSpeaking) {
                    // Full-duplex conversation: keep listening while Gama talks.
                    // AEC plus echo-aware language filtering prevents self-interruption.
                    handleBargeInAudio(rec, buffer, read, now)
                    continue
                }
                if (now < ignoreAudioUntil) {
                    preRoll = ByteArray(0)
                    wakePreRoll = ByteArray(0)
                    wakeRecognizer?.reset()
                    continue
                }
                bargePreRoll = ByteArray(0)

                // Highest-priority conversation exit path.
                // GAMA900_SUPERVISOR_SLEEP: partial ASR can never close the session.
                val dedicatedSleep = sleepRecognizer
                if (conversation.active && transcription == null &&
                    !ownerEnrollmentMode && !ownerRetryPending && dedicatedSleep != null) {
                    val sleepEndpoint = dedicatedSleep.acceptWaveForm(buffer, read)
                    if (sleepEndpoint) {
                        val sleepText = try {
                            JSONObject(dedicatedSleep.result).optString("text")
                        } catch (_: Exception) {
                            ""
                        }
                        val voiceEvidence =
                            GamaRecognitionPolicy.hasVoiceEvidence(audioMetrics) ||
                                audioMetrics.speechLikely
                        if (GamaSupervisor.acceptGoodbye(
                                text = sleepText,
                                sessionActive = conversation.active,
                                voiceEvidence = voiceEvidence,
                                finalEndpoint = true,
                            )) {
                            dedicatedSleep.reset()
                            wakeRecognizer?.reset()
                            rec.reset()
                            decoderActive = false
                            lastPartial = ""
                            preRoll = ByteArray(0)
                            wakePreRoll = ByteArray(0)
                            closeConversationForGoodbye()
                            continue
                        }
                        dedicatedSleep.reset()
                    }
                } else {
                    dedicatedSleep?.reset()
                }

                // Hotword path: while idle, do NOT gate "Gama" by amplitude. A
                // constrained Vosk recognizer continuously receives the raw mic
                // stream. The full recognizer stays asleep until the wake word is
                // actually heard, which is both cheaper and much more reliable.
                val idleHotwordMode = !conversation.active &&
                    !waitingForCommandStart && !commandSpeechStarted &&
                    transcription == null && !ownerEnrollmentMode && !ownerRetryPending
                val dedicatedWake = wakeRecognizer
                if (idleHotwordMode && dedicatedWake != null) {
                    wakePreRoll = (wakePreRoll + buffer.copyOf(read)).takeLast(16000).toByteArray()
                    val endpoint = dedicatedWake.acceptWaveForm(buffer, read)
                    var wakeText = ""
                    if (endpoint) {
                        wakeText = try { JSONObject(dedicatedWake.result).optString("text") } catch (_: Exception) { "" }
                    } else if (now - lastDedicatedWakePartialAt >= 60L) {
                        lastDedicatedWakePartialAt = now
                        wakeText = try { JSONObject(dedicatedWake.partialResult).optString("partial") } catch (_: Exception) { "" }
                    }
                    val wakeVoiceEvidence =
                        GamaRecognitionPolicy.hasVoiceEvidence(audioMetrics) ||
                            audioMetrics.speechLikely

                    val normalizedWake = VoicePolicy.normalize(wakeText)
                    val partialWakeStable = if (!endpoint && VoicePolicy.hasWake(normalizedWake)) {
                        if (normalizedWake == wakePartialCandidate &&
                            wakePartialFirstAt > 0L && now - wakePartialFirstAt <= 900L) {
                            wakePartialHits += 1
                        } else {
                            wakePartialCandidate = normalizedWake
                            wakePartialHits = 1
                            wakePartialFirstAt = now
                        }
                        wakePartialHits >= 6
                    } else {
                        if (endpoint || normalizedWake.isBlank() || !VoicePolicy.hasWake(normalizedWake)) {
                            wakePartialCandidate = ""
                            wakePartialHits = 0
                            wakePartialFirstAt = 0L
                        }
                        false
                    }

                    val wakeConfirmed = endpoint || partialWakeStable
                    if (wakeConfirmed && GamaSupervisor.acceptWake(
                            text = wakeText,
                            sessionActive = conversation.active,
                            voiceEvidence = wakeVoiceEvidence,
                        )) {
                        wakePartialCandidate = ""
                        wakePartialHits = 0
                        wakePartialFirstAt = 0L
                        dedicatedWake.reset()
                        rec.reset()
                        decoderActive = true
                        utteranceStartedAt = now
                        lastPartial = "gama"
                        // Feed the audio immediately before/around the hotword to the
                        // full decoder. Then open the conversation NOW, instead of
                        // spending ~0.5 s in hotword mode and losing the beginning of
                        // "Gama, faça X".
                        if (wakePreRoll.isNotEmpty()) rec.acceptWaveForm(wakePreRoll, wakePreRoll.size)
                        wakePreRoll = ByteArray(0)
                        acknowledgeWake(false)
                        beginCommandWindow()

                        // If the owner called only "Gama", acknowledge naturally.
                        // If they continued the sentence, the first partial cancels
                        // this prompt and the command is captured in one shot.
                        val wakeInteraction = interactionToken
                        uiHandler.postDelayed({
                            postAudio {
                                if (!running || !conversation.active ||
                                    interactionToken != wakeInteraction ||
                                    !waitingForCommandStart || commandSpeechStarted ||
                                    lastUsefulPartial.isNotBlank() ||
                                    speaking || ttsSpeaking) return@postAudio
                                armCommandWindowAfterTts = true
                                speak("Estou ouvindo.", false)
                            }
                        }, 650L)
                        continue
                    }
                    if (endpoint) dedicatedWake.reset()
                    continue
                } else if (idleHotwordMode) {
                    // Some Vosk model graphs do not accept constrained grammars. In
                    // that case reliability still wins over the old amplitude gate:
                    // keep the full decoder running continuously, but process only
                    // utterances that actually contain the wake word.
                    decoderActive = true
                    utteranceStartedAt = if (utteranceStartedAt == 0L) now else utteranceStartedAt
                    if (rec.acceptWaveForm(buffer, read)) {
                        val result = JSONObject(rec.result)
                        processFinal(result)
                        rec.reset()
                        lastPartial = ""
                        utteranceStartedAt = 0L
                    } else if (now - lastPartialAt >= 120L) {
                        lastPartialAt = now
                        val partial = JSONObject(rec.partialResult).optString("partial").trim()
                        if (partial.isNotBlank() && partial != lastPartial) {
                            lastPartial = partial
                            handlePartialText(partial)
                        }
                    }
                    continue
                } else {
                    wakePreRoll = ByteArray(0)
                    wakePartialCandidate = ""
                    wakePartialHits = 0
                    wakePartialFirstAt = 0L
                    dedicatedWake?.reset()
                }

                if (!shouldDecodeAudio(buffer, read)) {
                    // Guarde cerca de meio segundo antes do VAD disparar. Isso
                    // evita cortar o começo de “Gama” quando o usuário fala baixo.
                    preRoll = (preRoll + buffer.copyOf(read)).takeLast(24000).toByteArray()
                    continue
                }
                if (!decoderActive) {
                    decoderActive = true
                    utteranceStartedAt = now
                    lastSpeechAt = 0L
                    if (preRoll.isNotEmpty()) rec.acceptWaveForm(preRoll, preRoll.size)
                    preRoll = ByteArray(0)
                }
                if (rec.acceptWaveForm(buffer, read)) {
                    processFinal(JSONObject(rec.result))
                    rec.reset()
                    decoderActive = false
                    utteranceStartedAt = 0L
                    lastPartial = ""
                } else {
                    if (now - lastPartialAt >= 140L) {
                        lastPartialAt = now
                        val partial = JSONObject(rec.partialResult).optString("partial").trim()
                        if (partial.isNotBlank() && partial != lastPartial) {
                            lastPartial = partial
                            handlePartialText(partial)
                        }
                    }
                    // Only force a final after this decoder instance has actually
                    // seen speech. Without this guard, an open conversation could
                    // reset the recognizer every few milliseconds during silence
                    // and chop the next sentence before Vosk formed a partial.
                    if (GamaRecognitionPolicy.shouldForceEndpoint(
                            decoderActive = decoderActive,
                            utteranceStartedAt = utteranceStartedAt,
                            lastVoiceEvidenceAt = lastVoiceEvidenceAt,
                            lastUsefulPartialAt = lastUsefulPartialAt,
                            lastSpeechAt = lastSpeechAt,
                            now = now,
                        )) {
                        processFinal(JSONObject(rec.finalResult))
                        rec.reset()
                        decoderActive = false
                        utteranceStartedAt = 0L
                        lastPartial = ""
                        speechHoldUntil = 0L
                    }
                }
            }
        } finally {
            voiceReady = false
            lastAudioReadAt = 0L
            transcription?.error(android.speech.SpeechRecognizer.ERROR_AUDIO)
            transcription = null
            val finishingRecorder = audioRecord
            try { finishingRecorder?.stop() } catch (_: Exception) {}
            try { finishingRecorder?.let { GamaAudioNoiseSupervisor.detach(it) } } catch (_: Exception) {}
            try { finishingRecorder?.release() } catch (_: Exception) {}
            audioRecord = null
            try { recognizer?.close() } catch (_: Exception) {}
            recognizer = null
            try { wakeRecognizer?.close() } catch (_: Exception) {}
            wakeRecognizer = null
            wakePreRoll = ByteArray(0)
            wakePartialCandidate = ""
            wakePartialHits = 0
            wakePartialFirstAt = 0L
            try { sleepRecognizer?.close() } catch (_: Exception) {}
            sleepRecognizer = null
            lastDedicatedSleepPartialAt = 0L
            try { speakerModel?.close() } catch (_: Exception) {}
            speakerModel = null
            try { model?.close() } catch (_: Exception) {}
            model = null
            decoderActive = false
        }
    }

    private fun processFinal(json: JSONObject) {
        val finalText = json.optString("text").trim()
        val finalNow = SystemClock.elapsedRealtime()
        val spoken = GamaRecognitionPolicy.bestTranscript(
            finalText = finalText,
            recentPartial = lastUsefulPartial,
            partialAgeMs = if (lastUsefulPartialAt > 0L) finalNow - lastUsefulPartialAt else Long.MAX_VALUE,
        )
        lastUsefulPartial = ""
        lastUsefulPartialAt = 0L
        if (speaking || ttsSpeaking) {
            if (!conversation.active || spoken.isBlank()) return
            if (!VoicePolicy.shouldBargeIn(spoken, activeSpeechText, headsetAudioOutput(SystemClock.elapsedRealtime()))) return
            interruptSpeechForBargeIn()
        }
        transcription?.let { listener ->
            transcription = null
            finishCommandListening()
            listener.result(spoken)
            return
        }
        if (spoken.isBlank()) return
        // Modos explícitos de cadastro/confirmação precisam receber números reais.
        if (ownerEnrollmentMode) { handleOwnerEnrollmentSample(spoken, json); return }
        if (ownerRetryPending) { verifyChallenge(spoken, json); return }

        val postActionNow = SystemClock.elapsedRealtime()
        val confidence = averageRecognitionConfidence(json)
        val recentHumanSpeech = lastVoiceEvidenceAt > 0L &&
            postActionNow - lastVoiceEvidenceAt <= 2200L
        val postActionArtifact =
            (!recentHumanSpeech && postActionNow < postExternalNoiseGuardUntil &&
                VoicePolicy.looksLikePostActionNoise(spoken, confidence)) ||
            (!recentHumanSpeech && postActionNow < postExternalNumericGuardUntil &&
                VoicePolicy.looksLikeNumericOnly(spoken))
        if (postActionArtifact) {
            // Ex.: o ruído da troca para o WhatsApp sendo reconhecido como "7667".
            // Não enviamos isso para a IA e não respondemos em voz alta.
            recognizer?.reset()
            decoderActive = false
            lastPartial = ""
            AssistantRuntime.partial("")
            return
        }

        if (conversation.active || waitingForCommandStart || commandSpeechStarted) {
            val recentVoiceEvidence = lastVoiceEvidenceAt > 0L &&
                SystemClock.elapsedRealtime() - lastVoiceEvidenceAt <= 2600L
            if (!GamaRecognitionPolicy.shouldAcceptSessionFinal(
                    text = spoken,
                    commandSpeechStarted = commandSpeechStarted,
                    recentVoiceEvidence = recentVoiceEvidence,
                )) {
                // Background chatter/noise can occasionally produce a Vosk final
                // while a conversation window is open. Ignore it silently instead
                // of answering "não entendi" or executing a phantom command.
                recognizer?.reset()
                decoderActive = false
                lastPartial = ""
                AssistantRuntime.partial("")
                return
            }
            if (conversation.active) touchConversation()
            authorizeCommand(if (conversation.active) VoicePolicy.followUp(spoken) else VoicePolicy.command(spoken), json, fromVoice = true)
        } else if (GamaSupervisor.acceptWake(
                text = spoken,
                sessionActive = conversation.active,
                voiceEvidence = recentHumanSpeech,
            )) {
            val command = VoicePolicy.command(spoken)
            if (command.isBlank()) {
                acknowledgeWake()
            } else {
                acknowledgeWake(false)
                authorizeCommand(command, json, fromVoice = true)
            }
        }
    }

    private fun currentWakePhrase(): String = "gama"

    private fun wakePhraseIndex(text: String, phrase: String): Int = VoicePolicy.wakeIndex(text)

    private fun phraseCloseEnough(heard: String, target: String): Boolean {
        val h = norm(heard)
        val t = norm(target)
        if (h == t) return true
        if (t == "gama") return VoicePolicy.hasWake(h)

        val hw = h.split(" ").filter { it.isNotBlank() }
        val tw = t.split(" ").filter { it.isNotBlank() }
        if (tw.isEmpty()) return false
        val common = tw.count { word -> hw.contains(word) }
        return common >= maxOf(2, (tw.size * 0.75f).toInt())
    }

    private fun touchConversation() {
        if (!conversation.active) return
        conversationLastActivityAt = SystemClock.elapsedRealtime()
        val token = ++conversationIdleToken
        uiHandler.postDelayed({
            postAudio {
                if (!running || !conversation.active || token != conversationIdleToken) return@postAudio
                val now = SystemClock.elapsedRealtime()
                val idleFor = now - conversationLastActivityAt
                if (idleFor < CONVERSATION_IDLE_TIMEOUT_MS || speaking || ttsSpeaking ||
                    brainBusy || researchBusy || liveBusy || commandSpeechStarted) {
                    // Ainda existe atividade; um novo token agenda a próxima verificação.
                    touchConversation()
                    return@postAudio
                }
                requestGeneration++
                finishCommandListening()
                conversation.close()
                GamaSupervisor.onConversationClosed()
                ZevronContextResolver.reset()
                armCommandWindowAfterTts = false
                conversationLastActivityAt = 0L
                updateNotification("Em espera • diga Gama para iniciar outra conversa.")
                hideWakeUi()
            }
        }, CONVERSATION_IDLE_TIMEOUT_MS)
    }

    private fun beginCommandWindow() {
        if (conversation.active && isDeviceLocked()) {
    sendLockConversationUi(
        GamaLockConversationActivity.ACTION_LISTENING
    )
}
        armCommandWindowAfterTts = false
        waitingForCommandStart = true
        commandSpeechStarted = false
        lastUsefulPartial = ""
        lastUsefulPartialAt = 0L
        lastVoiceEvidenceAt = 0L
        val now = maxOf(SystemClock.elapsedRealtime(), ignoreAudioUntil)
        val duration = when {
            ownerRetryPending -> 9000L
            conversation.active -> CONVERSATION_IDLE_TIMEOUT_MS // Conversa continua sem repetir "Gama".
            else -> COMMAND_START_TIMEOUT_MS
        }
        commandWindow.arm(now, duration)
        ownerRetryUntil = if (ownerRetryPending) now + 15000L else 0L
        conversationUntil = now + duration
        val listeningStatus = when {
            ownerRetryPending -> "Aguardando confirmação..."
            SystemClock.elapsedRealtime() < externalLaunchStatusUntil -> "Abrindo aplicativo..."
            else -> "Ouvindo..."
        }
        updateNotification(listeningStatus)
    }

    private fun acknowledgeWake(speakAcknowledgement: Boolean = true) {
        transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
        transcription = null
        clearOwnerVoiceRetry()
        commandWindow.clear()
        queuedWakeCommand = null
        conversation.open()
        GamaSupervisor.onWakeAccepted()
        ZevronContextResolver.reset()
        touchConversation()
        interactionToken++
        waitingForCommandStart = false
        commandSpeechStarted = false
        conversationUntil = 0L
        wakeUntil = 0L
        lastWakeAt = SystemClock.elapsedRealtime()
        showWakeUi()
        animateOrbWake()
        uiHandler.removeCallbacks(uiTimeout)
        if (speakAcknowledgement) {
            armCommandWindowAfterTts = true
            updateNotification("Conversa contínua iniciada")
            speak("Estou ouvindo.", false)
        } else {
            armCommandWindowAfterTts = false
            updateNotification("Pedido reconhecido")
        }
    }

    private fun markCommandSpeechStarted() {
        if (!waitingForCommandStart) return
        waitingForCommandStart = false
        commandSpeechStarted = true
        interactionToken++
        commandWindow.speech(SystemClock.elapsedRealtime())
        touchConversation()
        conversationUntil = SystemClock.elapsedRealtime() + CONVERSATION_IDLE_TIMEOUT_MS
        uiHandler.removeCallbacks(uiTimeout)
        updateNotification("Ouvindo...")
    }

    private fun armResponseDeadline() {
        val token = ++responseWatchToken
        responsePending = true
        uiHandler.postDelayed({
            if (running && responsePending && responseWatchToken == token) {
                responsePending = false
                updateNotification("Pensando...")
                if (isDeviceLocked()) sendLockConversationUi(GamaLockConversationActivity.ACTION_THINKING)
            }
        }, RESPONSE_WATCHDOG_MS)
    }

    private fun markResponseStarted() {
        if (responsePending) {
            responsePending = false
            responseWatchToken++
        }
    }

    private fun finishCommandListening() {
        AssistantRuntime.partial("")
        commandWindow.clear()
        voicedBlocks = 0
        waitingForCommandStart = false
        commandSpeechStarted = false
        conversationUntil = 0L
        wakeUntil = 0L
        interactionToken++
    }

    private fun handlePartialText(raw: String) {
        transcription?.let { listener ->
            if (raw.isNotBlank()) { markCommandSpeechStarted(); listener.partial(raw) }
            return
        }
        if ((waitingForCommandStart || commandSpeechStarted) && !isDeviceLocked()) AssistantRuntime.partial(raw)
        val text = norm(raw)
        val partialNow = SystemClock.elapsedRealtime()
        if (text.isNotBlank() && text !in setOf("gama", "gamma") &&
            GamaRecognitionPolicy.hasVoiceEvidence(lastAudioMetrics)) {
            lastUsefulPartial = raw.trim()
            lastUsefulPartialAt = partialNow
            lastVoiceEvidenceAt = partialNow
            lastSpeechAt = partialNow
        }
        val partialToken = ++partialWakeToken
        if (text.isBlank() || speaking || ttsSpeaking || armCommandWindowAfterTts) return

        if (waitingForCommandStart) {
            if (GamaRecognitionPolicy.hasVoiceEvidence(lastAudioMetrics)) {
                markCommandSpeechStarted()
            }
            return
        }

        if (commandSpeechStarted) {
            touchConversation()
            conversationUntil = SystemClock.elapsedRealtime() + CONVERSATION_IDLE_TIMEOUT_MS
            return
        }

        val phrase = currentWakePhrase()
        val now = SystemClock.elapsedRealtime()
        val hasWake = wakePhraseIndex(text, phrase) >= 0 &&
            GamaSupervisor.acceptWake(
                text = text,
                sessionActive = conversation.active,
                voiceEvidence = GamaRecognitionPolicy.hasVoiceEvidence(lastAudioMetrics),
            )

        if (hasWake && now - lastWakePulse > 900L) {
            lastWakePulse = now
            showWakeUi()
            animateOrbWake()
            if (!conversation.active) scheduleUiTimeout(7000L)
            updateNotification("Gama reconhecido.")
        }

        // Se o usuário disser somente "Gama", não esperamos o endpoint final do Vosk.
        // O atraso curto é cancelado assim que a parcial cresce, portanto "Gama, abra o zap"
        // continua sendo processado como uma única frase completa.
        if (!conversation.active && text in setOf("gama", "gamma")) {
            uiHandler.postDelayed({
                postAudio {
                    if (!running || conversation.active || speaking || ttsSpeaking || partialToken != partialWakeToken) return@postAudio
                    if (norm(lastPartial) !in setOf("gama", "gamma")) return@postAudio
                    if (!GamaSupervisor.acceptWake(
                            text = lastPartial,
                            sessionActive = conversation.active,
                            voiceEvidence = GamaRecognitionPolicy.hasVoiceEvidence(lastAudioMetrics),
                        )) return@postAudio
                    recognizer?.reset()
                    decoderActive = false
                    lastPartial = ""
                    acknowledgeWake()
                }
            }, 480L)
        }
    }

    private fun isEndConversation(command: String): Boolean =
        ConversationSession.isGoodbye(command)

    private fun closeConversationForGoodbye() {
        requestGeneration++
        finishCommandListening()
        conversation.close()
        GamaSupervisor.onConversationClosed()
        ZevronContextResolver.reset()
        zevronPlanSteps.clear()
        zevronPlanRunning = false
        currentPlanStep = ""
        lastPlanStepMayContinue = true
        conversationIdleToken++
        conversationLastActivityAt = 0L
        conversationUntil = 0L
        wakeUntil = 0L
        creatorResearchUntil = 0L
        armCommandWindowAfterTts = false
        closeUiAfterSpeech = true
        queuedWakeCommand = null
        pendingSuggestedCommand = null
        pendingSuggestedAt = 0L
        updateNotification("Em espera • diga Gama quando precisar.")
        uiHandler.post {
            try { tts?.stop() } catch (_: Exception) {}
            speak("Certo. Vou ficar em espera. Diga Gama quando precisar.", true)
        }
    }

    fun endConversation() { postAudio { closeConversationForGoodbye() } }

    /** Stops TTS without resetting Vosk, preserving the utterance that interrupted Gama. */
    private fun interruptSpeechForBargeIn() {
        if (!conversation.active || !(speaking || ttsSpeaking)) return
        activeUtterance = null
        speaking = false
        ttsSpeaking = false
        closeUiAfterSpeech = false
        pendingSpeech = null
        pendingCloseAfterSpeech = false
        queuedSpeechText = null
        queuedSpeechCloseAfter = false
        queuedSpeechRecord = true
        activeSpeechText = null
        ttsRetryCount = 0
        ignoreAudioUntil = 0L
        armCommandWindowAfterTts = false
        waitingForCommandStart = false
        commandSpeechStarted = true
        touchConversation()
        conversationUntil = SystemClock.elapsedRealtime() + CONVERSATION_IDLE_TIMEOUT_MS
        uiHandler.post {
            try { tts?.stop() } catch (_: Exception) {}
            setOrbListening()
        }
        updateNotification("Ouvindo sua interrupção...")
    }

    private fun audioAmplitude(buffer: ByteArray, read: Int): Double {
        var total = 0.0
        var count = 0
        var i = 0
        while (i + 1 < read) {
            val value = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 255)).toDouble()
            total += kotlin.math.abs(value)
            count++
            i += 8
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun shouldDecodeBargeInAudio(buffer: ByteArray, read: Int, now: Long): Boolean {
        if (!conversation.active || now < ignoreAudioUntil) return false
        val amplitude = audioAmplitude(buffer, read)
        val headphones = headsetAudioOutput(now)
        val multiplier = if (headphones) 1.30 else 2.15
        val floor = if (headphones) 42.0 else 90.0
        val ceiling = if (headphones) 780.0 else 1600.0
        val gate = maxOf(floor, noiseFloor * multiplier).coerceAtMost(ceiling)
        val voiced = amplitude >= gate
        if (voiced) {
            lastSpeechAt = now
            speechHoldUntil = now + 1200L
        }
        return voiced || decoderActive || now < speechHoldUntil
    }

    private fun handleBargeInAudio(
        rec: Recognizer,
        buffer: ByteArray,
        read: Int,
        now: Long,
    ) {
        if (!conversation.active) {
            bargePreRoll = ByteArray(0)
            return
        }
        if (!shouldDecodeBargeInAudio(buffer, read, now)) {
            bargePreRoll = (bargePreRoll + buffer.copyOf(read)).takeLast(10000).toByteArray()
            return
        }
        if (!decoderActive) {
            decoderActive = true
            utteranceStartedAt = now
            if (bargePreRoll.isNotEmpty()) rec.acceptWaveForm(bargePreRoll, bargePreRoll.size)
            bargePreRoll = ByteArray(0)
        }
        if (rec.acceptWaveForm(buffer, read)) {
            val result = JSONObject(rec.result)
            val text = result.optString("text").trim()
            if (VoicePolicy.shouldBargeIn(text, activeSpeechText, headsetAudioOutput(now))) {
                interruptSpeechForBargeIn()
                processFinal(result)
            } else {
                rec.reset()
                decoderActive = false
                lastPartial = ""
            }
            return
        }
        if (now - lastPartialAt >= 120L) {
            lastPartialAt = now
            val partial = JSONObject(rec.partialResult).optString("partial").trim()
            if (partial.isNotBlank()) {
                lastPartial = partial
                if (VoicePolicy.shouldBargeIn(partial, activeSpeechText, headsetAudioOutput(now))) {
                    interruptSpeechForBargeIn()
                    AssistantRuntime.partial(partial)
                }
            }
        }
    }

    /** Tap the floating orb to interrupt; voice barge-in also works during a session. */
    fun interruptSpeechForUser() { postAudio {
        if (!conversation.active || !(speaking || ttsSpeaking)) return@postAudio
        activeUtterance = null // Ignore the asynchronous onStop for the previous TTS.
        speaking = false
        ttsSpeaking = false
        closeUiAfterSpeech = false
        pendingSpeech = null
        pendingCloseAfterSpeech = false
        queuedSpeechText = null
        queuedSpeechCloseAfter = false
        queuedSpeechRecord = true
        activeSpeechText = null
        ttsRetryCount = 0
        recognizer?.reset()
        decoderActive = false
        voicedBlocks = 0
        ignoreAudioUntil = 0L
        uiHandler.post { tts?.stop(); setOrbListening() }
        beginCommandWindow()
        updateNotification("Estou ouvindo. Pode continuar, senhor.")
    } }

    private fun headsetAudioOutput(now: Long): Boolean {
        if (now - headphoneCheckAt < 2000L) return headphoneOutput
        headphoneCheckAt = now
        val outputs = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) { return false }
        headphoneOutput = outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        return headphoneOutput
    }

    private fun speakCommandReply(text: String, privateData: Boolean, closeAfter: Boolean = true) {
        try {
            val clean = text.filter { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }.take(1400)
            if (privateData) {
                val addressed = GamaPersona.formalAddress(
                    clean,
                    OwnerIdentity.treatment(this),
                    OwnerIdentity.name(this)
                )
                speak(addressed, closeAfter, false)
            } else {
                speak(clean, closeAfter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha isolada ao preparar resposta falada", e)
            setConversationVisual(GamaEnergyView.Mode.LISTENING)
            postAudio {
                if (conversation.active) beginCommandWindow()
            }
        }
    }

    private fun scheduleAutomationTick() {
        val token = ++automationTickToken
        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!running || token != automationTickToken) return
                val due = try { automationEngine.dueRules() } catch (e: Exception) {
                    Log.e(TAG, "Falha isolada no motor de automação", e)
                    emptyList()
                }
                due.forEach { rule -> postAudio { executeAutomationRule(rule) } }
                val now = SystemClock.elapsedRealtime()
                if (now - lastCalendarAwarenessCheck >= 120_000L) {
                    lastCalendarAwarenessCheck = now
                    thread(name = "gama-ambient-awareness") {
                        GamaCalendarAwareness.poll(applicationContext)?.let { event ->
                            GamaEventHub.record(applicationContext, event)
                        }
                        GamaSystemAwareness.poll(applicationContext)?.let { event ->
                            GamaEventHub.record(applicationContext, event)
                        }
                    }
                }
                if (running && token == automationTickToken) uiHandler.postDelayed(this, 30_000L)
            }
        }, 8_000L)
    }

    private fun executeAutomationRule(rule: GamaAutomationEngine.Rule) {
        if (!running) return
        if (speaking || ttsSpeaking || brainBusy || liveBusy || researchBusy) {
            uiHandler.postDelayed({ if (running) postAudio { executeAutomationRule(rule) } }, 10_000L)
            return
        }
        if (rule.actionKind == AutomationActionKind.SPEAK) {
            speak(rule.action, true)
            return
        }
        val command = rule.action.trim()
        if (command.isBlank()) return
        if (IdentityIntent.requiresDeviceProof(command) && !hasTrustedOwnerSession()) {
            pendingAutomationAfterUnlock = rule
            updateNotification("Automação aguardando desbloqueio: ${rule.description.take(90)}")
            return
        }
        automationExecuting = true
        try {
            markResponseStarted()
            executeAndReply(command)
        } catch (e: Exception) {
            Log.e(TAG, "Automação falhou sem derrubar o Gama", e)
            updateNotification("Uma automação falhou e foi isolada. O Gama continua ativo.")
        } finally {
            automationExecuting = false
        }
    }

    private fun handleAutomationVoiceCommand(raw: String, normalized: String): Boolean {
        CrashRecorder.markComponent("automation")

        val now = SystemClock.elapsedRealtime()
        val pending = pendingAutomationDraft
        val pendingFresh = pending != null && pendingAutomationUntil > now

        if (pendingFresh && AutomationCommandRouter.isCancellation(normalized)) {
            pendingAutomationDraft = null
            pendingAutomationUntil = 0L
            speak("Certo. Não salvei a automação.", true)
            return true
        }
        if (pendingFresh && AutomationCommandRouter.isConfirmation(normalized)) {
            if (isDeviceLocked()) {
                speak("Desbloqueie o Android para salvar automações.", true)
                return true
            }
            val saved = automationEngine.add(pending!!)
            pendingAutomationDraft = null
            pendingAutomationUntil = 0L
            speak("Automação salva. ${saved.description}.", true)
            return true
        }
        if (!pendingFresh) {
            pendingAutomationDraft = null
            pendingAutomationUntil = 0L
        }

        if (!AutomationCommandRouter.looksLikeAutomationCommand(raw)) return false
        if (isDeviceLocked()) {
            pendingCommand.set(raw, now)
            speak(requestSecureUnlockReply(), true)
            return true
        }

        when {
            AutomationCommandRouter.isListRequest(raw) -> {
                speak(automationEngine.describeRules(), true)
                return true
            }
            AutomationCommandRouter.isPauseAll(raw) -> {
                val count = automationEngine.pauseAll()
                speak("Pausei $count automação${if (count == 1) "" else "ões"}.", true)
                return true
            }
            AutomationCommandRouter.isResumeAll(raw) -> {
                val count = automationEngine.resumeAll()
                speak("Ativei $count automação${if (count == 1) "" else "ões"}.", true)
                return true
            }
            AutomationCommandRouter.isDeleteAll(raw) -> {
                val count = automationEngine.deleteAll()
                speak("Apaguei $count automação${if (count == 1) "" else "ões"}.", true)
                return true
            }
            AutomationCommandRouter.isDeleteLast(raw) -> {
                val removed = automationEngine.deleteLast()
                speak(if (removed == null) "Você não tem automações salvas." else "Apaguei a última automação: ${removed.description}.", true)
                return true
            }
        }

        val draft = AutomationCommandRouter.parseCreate(raw)
        if (draft == null) {
            speak(
                "Entendi que você quer criar uma automação, mas essa ação não pode rodar sozinha ou eu não entendi a regra. " +
                    "Por exemplo: todo dia às sete abra o Spotify, ou quando eu conectar o fone abra o Spotify.",
                true
            )
            return true
        }
        pendingAutomationDraft = draft
        pendingAutomationUntil = now + 60_000L
        pendingSuggestedCommand = null
        pendingSuggestedAt = 0L
        speak("Posso salvar esta automação: ${draft.description}?", true)
        return true
    }

    private fun withHabitSuggestion(reply: String, command: String): String {
        if (automationExecuting || pendingAutomationDraft != null) return reply
        val draft = try { automationEngine.observeSafeHabit(command) } catch (_: Exception) { null } ?: return reply
        pendingAutomationDraft = draft
        pendingAutomationUntil = SystemClock.elapsedRealtime() + 60_000L
        return "$reply Percebi que você costuma fazer isso neste horário. Quer que eu automatize para você?"
    }

    private fun openKnownAppFastLane(command: String, sequence: Long): Boolean {
        val app = GamaAppRegistry.resolveOpenRequest(command) ?: return false

        if (isDeviceLocked()) {
            pendingCommand.set(command, SystemClock.elapsedRealtime())
            speak(requestSecureUnlockReply(), true)
            return true
        }

        GamaSupervisor.onExecuting()
        val result = GamaAppRegistry.launch(this, app, attempt = 0)
        if (!result.started) {
            speak("Não consegui iniciar ${app.label} por nenhuma rota disponível neste celular.", true)
            return true
        }

        ZevronConversationEngine.rememberSubject(this, app.label)
        GamaContinuityMemory.recordAction(this, "aplicativo solicitado", app.label)
        updateNotification("Abrindo ${app.label} • verificando a tela...")
        externalLaunchStatusUntil = SystemClock.elapsedRealtime() + 6_500L
        verifyKnownAppLaunch(app, sequence, attempt = 0)
        return true
    }

    /**
     * Never treats an accepted Android launch call as proof that the app is on
     * screen. On Samsung/Android versions where one route is ignored, retry a
     * different route and only announce success after Accessibility confirms
     * the foreground package.
     */
    private fun verifyKnownAppLaunch(
        app: GamaAppRegistry.AppSpec,
        sequence: Long,
        attempt: Int,
    ) {
        val delay = if (attempt == 0) 1_250L else 950L
        uiHandler.postDelayed({
            if (!running || sequence != userCommandSequence) return@postDelayed

            if (!GamaScreenContextService.isConnected()) {
                val reply =
                    "Enviei o comando para abrir ${app.label}. Para eu confirmar se a tela realmente abriu, mantenha Gama Contexto da Tela ativado."
                GamaTaskMemory.rememberResult(this, reply)
                speak(reply, closeAfter = false)
                return@postDelayed
            }

            val visiblePackage = GamaScreenContextService.currentPackageName()
            if (GamaAppRegistry.isExpectedPackage(app, visiblePackage)) {
                GamaSupervisor.onVerifying()
                val reply = "${app.label} aberto e confirmado na tela."
                GamaTaskMemory.rememberResult(this, reply)
                GamaContinuityMemory.recordAction(this, "abrir ${app.label}", reply)
                speak(reply, closeAfter = false)
                return@postDelayed
            }

            if (attempt < 3) {
                updateNotification(
                    "${app.label} ainda não apareceu • tentando outra rota (${attempt + 2}/4)..."
                )
                val retry = GamaAppRegistry.launch(this, app, attempt = attempt + 1)
                if (retry.started) {
                    verifyKnownAppLaunch(app, sequence, attempt + 1)
                    return@postDelayed
                }
            }

            GamaSupervisor.onVerifying()
            val where = visiblePackage?.takeIf { it.isNotBlank() }
            val reply = if (where == null) {
                "Tentei abrir ${app.label} por todas as rotas, mas o Android não confirmou a tela."
            } else {
                "Tentei abrir ${app.label} por todas as rotas, mas a tela permaneceu em outro aplicativo."
            }
            GamaTaskMemory.rememberResult(this, reply)
            GamaContinuityMemory.recordAction(this, "abrir ${app.label}", reply)
            speak(reply, closeAfter = false)
        }, delay)
    }

    private fun executeAndReply(command: String, fromPlan: Boolean = false) {
        val voiceFocusCommand = norm(command)
        if (!fromPlan && voiceFocusCommand in setOf(
                "cadastre minha voz", "cadastrar minha voz", "cadastre a minha voz",
                "aprenda minha voz", "aprenda a minha voz"
            )) {
            if (isDeviceLocked()) {
                speak("Desbloqueie o Android para cadastrar o foco da sua voz.", true)
            } else {
                enrollmentRequested = true
                startEnrollmentWhenReady()
            }
            return
        }
        if (!fromPlan && voiceFocusCommand in setOf(
                "apague minha voz", "esqueca minha voz", "esqueça minha voz",
                "remova meu perfil de voz"
            )) {
            deleteEnrolledVoice()
            speak("Perfil de foco vocal apagado. A segurança do Android não foi alterada.", true)
            return
        }

        // ZEVRON105_JARVIS_PLANNER
        // A fresh user command always cancels the remaining automatic plan.
        // Only explicit sequencing language creates a new plan.
        if (!fromPlan) {
            if (GamaConversationRepair.isResumeRequest(command)) {
                val mission = GamaMissionStore.resumable(this)
                if (mission != null) {
                    zevronPlanSteps.clear()
                    mission.remaining.forEach(zevronPlanSteps::add)
                    zevronPlanRunning = true
                    lastPlanStepMayContinue = true
                    val first = zevronPlanSteps.poll()
                    if (!first.isNullOrBlank()) {
                        currentPlanStep = first
                        GamaMissionStore.advance(this, first, zevronPlanSteps.toList())
                        updateNotification("Retomando a tarefa...")
                        executeAndReply(first, fromPlan = true)
                        return
                    }
                }
            }

            if (zevronPlanRunning && zevronPlanSteps.isNotEmpty()) {
                GamaMissionStore.pause(this, zevronPlanSteps.toList())
            }
            zevronPlanSteps.clear()
            zevronPlanRunning = false
            currentPlanStep = ""
            lastPlanStepMayContinue = true
            ZevronPlanner.plan(command)?.let { plan ->
                GamaMissionStore.start(this, command, plan.steps)
                plan.steps.drop(1).forEach(zevronPlanSteps::add)
                zevronPlanRunning = true
                lastPlanStepMayContinue = true
                currentPlanStep = plan.steps.first()
                GamaMissionStore.advance(this, plan.steps.first(), zevronPlanSteps.toList())
                updateNotification("Executando ${plan.size} etapas...")
                executeAndReply(plan.steps.first(), fromPlan = true)
                return
            }
        }

        // GAMA79_LOCAL_FIRST_ROUTING
        userCommandSequence += 1L
        // GAMA91_NOISE_COMMAND_GATE
        val gamaNoiseDecision = GamaSpeechNoisePolicy.evaluate(command)
        if (!gamaNoiseDecision.accept) {
            gamaNoiseDecision.reply?.let { speak(it) }
            return
        }

        val gamaCommandSequence = userCommandSequence
        val gamaDirectCommand = norm(command)

        // GAMA109_LOCKSCREEN_PUBLIC_FAST_LANE
        // Public device facts must work on the lock screen without authentication.
        // This path runs before face/private-command gates so asking the time can
        // never trigger an unlock ceremony.
        if (isTimeQuestion(gamaDirectCommand)) {
            markResponseStarted()
            speak(currentTimeReply(), false)
            return
        }

        // GAMA106_DIRECT_ACTION_FAST_LANE
        // Device actions must execute through verified Android tools before any LLM path.
        if (isLockCommand(gamaDirectCommand)) {
            speak(lockScreenReply())
            return
        }

        // Known installed apps are tools, not concepts for the LLM.
        // Opening WhatsApp never enters the WhatsApp messaging parser.
        if (openKnownAppFastLane(command, gamaCommandSequence)) return

        WhatsAppCommandParser.parse(command)?.let { request ->
            if (isDeviceLocked()) {
                pendingCommand.set(command, SystemClock.elapsedRealtime())
                speak(requestSecureUnlockReply(), true)
                return
            }
            if (request.message.isNullOrBlank()) {
                speak(handleWhatsAppRequest(request))
            } else {
                sendWhatsAppReliableAsync(request.target, request.message)
            }
            return
        }

        // ZEVRON_FIX97_UNIFIED_ROUTER
        ZevronLocalRouter.handle(
            this,
            command
        )?.let { zevronReply ->
            speak(zevronReply)
            return
        }

        // GAMA2000_LOCAL_VISION_ROUTER
        if (!isDeviceLocked() && GamaVisionCore.recognizes(command)) {
            val visionSequence = userCommandSequence
            setConversationVisual(GamaEnergyView.Mode.THINKING)
            updateNotification("Analisando a tela localmente...")

            GamaVisionCore.captureTextAsync(applicationContext) { visibleText ->
                uiHandler.post {
                    if (!running || visionSequence != userCommandSequence) return@post

                    if (visibleText.isBlank()) {
                        speak("Não consegui obter texto visível dessa tela agora.", true)
                        return@post
                    }

                    if (GamaVisionCore.requiresReasoning(command)) {
                        answerWithBrainAsync(
                            ZevronJarvisCore.brainInstruction(
                                command.trim() +
                                    "\nContexto visual extraído localmente por Accessibility e OCR: " +
                                    visibleText.take(6000)
                            )
                        )
                    } else {
                        speak(GamaVisionCore.spokenReadReply(visibleText))
                    }
                }
            }
            return
        }

        // GAMA92_TIME_AND_SCREEN_FAST_LANES
        GamaScreenFastLane.handle(this, command)?.let {
            speak(it)
            return
        }

        if (GamaTimeAwareBriefing.handle(this, command) { timeReply ->
            uiHandler.post {
                if (userCommandSequence == gamaCommandSequence) speak(timeReply)
            }
        }) {
            return
        }


        // GAMA82_FACE_GATE
// Locked private requests and explicit unlock commands are intercepted
// before proposals, calendar, Internet and brain fallbacks.
if (GamaFaceAccessGate.handle(
      this,
      command,
      onApproved = { approvedCommand ->
          uiHandler.post {
              executeAndReply(approvedCommand)
          }
      },
      onStatus = { faceStatus ->
          uiHandler.post {
              speak(faceStatus)
          }
      },
  )) {
  return
}

        // GAMA81_SUPER_ASSISTANT_CORE
        // Proposal/confirmation and explicit screen-context commands are handled
        // before calendar, Internet and brain fallbacks.
        if (GamaSuperAssistantCore.handle(this, command) { superAnswer ->
            uiHandler.post {
                if (userCommandSequence == gamaCommandSequence) {
                    speak(superAnswer)
                }
            }
        }) {
            return
        }

        // GAMA89_DAY_BRIEFING
        GamaDayBriefing.classify(command)?.let { period ->
            GamaDayBriefing.buildAsync(this, period) { dayReply ->
                uiHandler.post {
                    if (userCommandSequence == gamaCommandSequence) speak(dayReply)
                }
            }
            return
        }

        GamaMorningBriefing.handleSleep(this, command)?.let {
            speak(it)
            return
        }

        if (GamaMorningBriefing.isMorningCommand(command)) {
            GamaMorningBriefing.buildAsync(this) { briefing ->
                uiHandler.post {
                    if (userCommandSequence == gamaCommandSequence) {
                        speak(briefing)
                    }
                }
            }
            return
        }

        // Calendar commands are always resolved locally. A partial calendar
        // request receives a local clarification instead of reaching Gemma.
        GamaCalendarManager.handle(this, command)?.let {
            speak(it)
            return
        }

        // GAMA80_INTERNET_ROUTING
        // Public/current-information queries only. Private/device commands remain local.
        if (GamaOnlineRouter.handleAsync(this, command) { onlineAnswer ->
            uiHandler.post {
                if (userCommandSequence == gamaCommandSequence) {
                    speak(onlineAnswer)
                }
            }
        }) {
            return
        }

        // GAMA67_STABILITY_LOCAL_ENTRY
        if (SessionExitPolicy.shouldEnd(command)) {
            endConversation()
            return
        }
        if (GamaVoiceCatalog.looksLikeSettingsCommand(command)) {
            val voiceIntent = android.content.Intent(this, GamaVoiceSettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(voiceIntent) }
            speak("Abrindo Voz do Gama.")
            return
        }

        if (!running) return
        if (isEndConversation(command)) {
            closeConversationForGoodbye()
            return
        }
        if (AccessPolicy.requiresUnlock(isDeviceLocked(), isSafeWhileLocked(command))) {
            pendingCommand.set(command, SystemClock.elapsedRealtime())
            speak(requestSecureUnlockReply(), true)
            return
        }
        setConversationVisual(GamaEnergyView.Mode.THINKING)
        // Pedidos privados continuam na sessão de voz, mas não entram no histórico enviado à IA.
        val privateData = IdentityIntent.requiresDeviceProof(command)
        if (conversation.active && isDeviceLocked()) {
    sendLockConversationUi(
        GamaLockConversationActivity.ACTION_THINKING
    )
}
        if (!privateData) {
            AssistantRuntime.add(true, command)
            conversation.userSaid(command)
            if (!isDeviceLocked()) GamaContinuityMemory.recordUser(this, command)
        }
        if (conversation.active) touchConversation()
        IdentityIntent.publicReply(command)?.let { reply ->
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }
        IdentityIntent.ownerReply(this, command)?.let { reply ->
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }
        GamaProfile.handle(this, command)?.let { reply ->
            conversationUntil = 0L
            wakeUntil = 0L
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }
        handleCalendarVoiceAction(command)?.let { reply ->
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }
        WhatsAppCommandParser.parse(command)?.let { request ->
            val reply = handleWhatsAppRequest(request)
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }
        CommandRouter.appToOpen(command)?.let { app ->
            deferredLaunch = false
            val baseReply = openByLabel(app)
            val reply = withHabitSuggestion(baseReply, "abra $app")
            conversationUntil = 0L
            wakeUntil = 0L
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }
        if (isWikipediaCommand(command)) {
            researchWikipediaAsync(
                query = extractWikipediaQuery(norm(command)),
                creatorMode = false
            )
            return
        }

        val normalizedCommand = norm(command)

        // ZEVRON105_CONTEXTUAL_PERCEPTION
        // Vague phrases such as "o que é isso?" use the screen only when the
        // user has enabled the accessibility bridge, the device is unlocked,
        // and a fresh snapshot is actually available. Gama never pretends to
        // see a screen it did not receive from Android.
        if (!isDeviceLocked() && ZevronPerceptionPolicy.wantsVisibleContext(normalizedCommand)) {
            val visible = GamaScreenContextService.snapshotNow()
            if (visible != null) {
                val screenText = visible.plainText(3200)
                if (screenText.isNotBlank()) {
                    answerWithBrainAsync(
                        ZevronJarvisCore.brainInstruction(
                            "$normalizedCommand\nContexto visível fornecido pelo Android agora: $screenText"
                        )
                    )
                    return
                }
            }
        }

        if (FreshnessPolicy.requiresFreshSource(normalizedCommand)) {
            val liveSequence = userCommandSequence
            val liveQuery = FreshnessPolicy.researchQuery(normalizedCommand)
            setConversationVisual(GamaEnergyView.Mode.THINKING)
            updateNotification("Consultando uma fonte atual...")
            Thread({
                val liveAnswer = runCatching {
                    GamaInternetHub.liveSearch(liveQuery)
                }.getOrElse {
                    "Não consegui consultar uma fonte atual agora. Posso tentar novamente."
                }
                uiHandler.post {
                    if (running && userCommandSequence == liveSequence) {
                        speak(liveAnswer)
                    }
                }
            }, "gama-fresh-search").apply { isDaemon = true; start() }
            return
        }
        if (SmartFeatures.isAsync(normalizedCommand)) {
            answerWithLiveDataAsync(normalizedCommand, privateData)
            return
        }

        // Ações do aparelho nunca são delegadas à IA para "adivinhar" o que executar.
        if (DeviceActionSafety.looksLikeUnresolvedAction(normalizedCommand)) {
            val reply = "Entendi que você quer executar uma ação, mas não entendi qual. Pode dizer de outro jeito?"
            updateNotification(reply)
            speakCommandReply(reply, privateData)
            return
        }

        // Conversa aberta usa o processo :gama_brain. Um crash nativo do modelo não derruba o microfone.
        if (!isFastDeviceCommand(normalizedCommand)) {
                    // GAMA67_STABILITY_BRAIN_GATE
        if (LocalCommandIsolationPolicy.mustNeverUseBrain(normalizedCommand)) {
            if (LocalCommandIsolationPolicy.isMessageRead(normalizedCommand)) {
                speak(messagesReply(normalizedCommand))
            } else {
                speak("Não consegui concluir esse comando local agora. Posso tentar novamente.")
            }
            return
        }
answerWithBrainAsync(
            ZevronJarvisCore.brainInstruction(normalizedCommand)
        ) // ZEVRON105_JARVIS_BRAIN_PROMPT
            return
        }

        deferredLaunch = false
        val executedReply = try {
            execute(command)
        } catch (e: Throwable) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            Log.e(TAG, "Falha em comando local: $command", e)
            "Tive um erro interno ao executar esse comando, mas continuei ativo. Pode tentar novamente."
        }
        val baseReply = if (deferredLaunch) "Toque em Continuar pedido na notificação do Gama." else executedReply
        val reply = withHabitSuggestion(baseReply, command)

        conversationUntil = 0L
        wakeUntil = 0L

        updateNotification(reply)

        // Não reabre a bolinha durante a conversa contínua.
        speakCommandReply(reply, privateData)
    }

    private fun handleCalendarVoiceAction(raw: String): String? {
        CrashRecorder.markComponent("calendar")

        val now = SystemClock.elapsedRealtime()
        val waitingForTitle = pendingCalendarTitleUntil > now

        if (waitingForTitle && !CalendarCommandRouter.looksLikeCreateRequest(raw)) {
            if (CalendarCommandRouter.canUseAsFollowUp(raw)) {
                pendingCalendarTitleUntil = 0L
                return openCalendarDraft(raw.trim())
            }
            pendingCalendarTitleUntil = 0L
        }

        if (!CalendarCommandRouter.looksLikeCreateRequest(raw)) return null

        val payload = CalendarCommandRouter.payload(raw)
        if (payload.isBlank()) {
            pendingCalendarTitleUntil = now + 90_000L
            return "Certo. O que deseja marcar na agenda?"
        }

        pendingCalendarTitleUntil = 0L
        return openCalendarDraft(payload)
    }

    private fun openCalendarDraft(title: String): String {
        val cleanTitle = title.trim().take(180)
        if (cleanTitle.isBlank()) {
            pendingCalendarTitleUntil = SystemClock.elapsedRealtime() + 90_000L
            return "O que deseja marcar na agenda?"
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.Events.TITLE, cleanTitle)
        }
        launchSafely(intent)
        return "Abri o calendário com o compromisso preenchido para você confirmar."
    }

    private fun isMessageReadCommand(c: String): Boolean =
        MessageCommandRouter.isReadRequest(c)

    private fun isFastDeviceCommand(c: String): Boolean {
        if (AutomationCommandRouter.looksLikeAutomationCommand(c)) return true
        if (IdentityIntent.kind(c) != IdentityIntent.Kind.NONE) return true
        if (c in setOf("diagnostico", "diagnóstico", "ultimo erro", "último erro", "qual foi o ultimo erro", "qual foi o último erro")) return true
        if (WhatsAppCommandParser.looksLikeSendCommand(c)) return true
        if (CommandRouter.appToOpen(c) != null || CommandRouter.looksLikeOpenRequest(c)) return true
        if (DeviceExtras.known(c) || SmartFeatures.isLocal(c) || SmartFeatures.isCalendar(c) || isOpenAppCommand(c)) return true

        val compact = c.replace(" ", "")

        return (
            isUnlockCommand(c) ||
            ((c.contains("bloque") || c.contains("trave")) &&
                (c.contains("tela") || c.contains("celular") || c.contains("telefone"))) ||
            isMessageReadCommand(c) ||
            isTimeQuestion(c) || c.contains("bateria") ||
            c.contains("camera") ||
            c.contains("configurac") ||
            c.contains("ajustes") ||
            c.contains("tela inicial") ||
            c == "inicio" ||
            c.contains("volume") ||
            c.contains("pause") ||
            c.contains("pausar") ||
            c.contains("continue") ||
            c.contains("retome") ||
            c.contains("reprodu") ||
            c.contains("proxima musica") ||
            c.contains("proxima faixa") ||
            c.contains("musica anterior") ||
            c.contains("faixa anterior") ||
            c.contains("lanterna") ||
            compact == "spotify" ||
            compact == "whatsapp" ||
            compact == "instagram" ||
            compact == "youtube" ||
            compact == "tiktok"
        )
    }

    private fun answerWithLiveDataAsync(command: String, privateData: Boolean = false) {
        if (liveBusy) { speak("Já estou consultando os dados solicitados.", true); return }
        val generation = requestGeneration
        liveBusy = true
    updateNotification("Consultando dados autorizados...")
    uiHandler.postDelayed({
        if (running && liveBusy && generation == requestGeneration) {
            liveBusy = false
            requestGeneration++
            speak("A consulta demorou demais. Pode pedir novamente.", true)
        }
    }, 30000L)
        setConversationVisual(GamaEnergyView.Mode.THINKING)
        thread(name = "gama-live-info") {
            val answer = try { SmartFeatures.respond(applicationContext, command) }
                catch (_: Throwable) { "Não consegui consultar os dados agora." }
            liveBusy = false
            if (!running || generation != requestGeneration) return@thread
            uiHandler.post {
                if (!running || generation != requestGeneration) return@post
                if (isDeviceLocked()) { speak("Desbloqueie o Android para continuar.", true); return@post }
                conversationUntil = 0L; wakeUntil = 0L
                updateNotification(answer); speakCommandReply(answer, privateData)
            }
        }
    }

    private fun answerWithBrainAsync(question: String) {
        // GAMA76_BRAIN_RETRY_CAPTURE
        if (brainRetryPrompt != question) {
            brainRetryPrompt = question
            brainRetryAttempt = 0
        }
        brainRetrySequence = userCommandSequence

        uiHandler.postDelayed({
            runCatching { brainProcessSupervisor?.bindStartedBrain() }
        }, 180L)

        CrashRecorder.markComponent("brain IPC")

        if (brainBusy) {
            speak("Ainda estou terminando a resposta anterior.", true)
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < brainCooldownUntil) {
            speak("Não consegui concluir essa resposta agora. Posso tentar novamente.", true)
            return
        }

        val requestId = ++brainRequestSerial
        val generation = requestGeneration
        val transientContext = if (conversation.active) {
            listOfNotNull(
                conversation.historyBeforeCurrent(),
                ZevronContextResolver.brainContext()
            )
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        } else {
            null
        }
        val dialogueContext = ZevronJarvisCore.enrichBrainContext(this, transientContext)

        brainBusy = true
        activeBrainRequestId = requestId
        activeBrainGeneration = generation
        activeBrainPid = 0
        brainHealthToken++
        updateNotification("Pensando...")
        setConversationVisual(GamaEnergyView.Mode.THINKING)

        try {
            startService(
                BrainProcessProtocol.requestIntent(
                    this,
                    requestId,
                    question,
                    dialogueContext
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível iniciar o processo isolado da IA", e)
            failIsolatedBrain(
                requestId,
                "Não consegui concluir essa resposta agora. Posso tentar novamente."
            )
            return
        }

        val timeoutToken = brainHealthToken
        uiHandler.postDelayed({
            if (!running || timeoutToken != brainHealthToken) return@postDelayed
            if (brainBusy && activeBrainRequestId == requestId) {
                failIsolatedBrain(
                    requestId,
                    "Não consegui concluir essa resposta agora. Os recursos do celular continuam disponíveis."
                )
            }
        }, 45_000L)
    }

    private fun scheduleBrainProcessHealthCheck(requestId: Long) {
        val token = brainHealthToken
        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!running || token != brainHealthToken) return
                if (!brainBusy || requestId != activeBrainRequestId) return

                val pid = activeBrainPid
                if (pid > 0 && !isBrainProcessAlive(pid)) {
                    failIsolatedBrain(
                        requestId,
                        "Não consegui concluir essa resposta agora. Posso tentar novamente."
                    )
                    return
                }

                uiHandler.postDelayed(this, 1200L)
            }
        }, 1200L)
    }

    private fun isBrainProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            val manager = getSystemService(ActivityManager::class.java)
            manager.runningAppProcesses?.any { process ->
                process.pid == pid && process.processName.endsWith(":gama_brain")
            } == true
        } catch (_: Exception) {
            true
        }
    }

    private fun finishIsolatedBrainResult(
        requestId: Long,
        rawAnswer: String,
        success: Boolean
    ) {
        if (requestId != activeBrainRequestId) return
        val generation = activeBrainGeneration
        brainHealthToken++
        brainBusy = false
        activeBrainRequestId = 0L
        activeBrainPid = 0

        if (!running || generation != requestGeneration) return

        val answer = rawAnswer.trim().ifBlank {
            "Não consegui concluir essa resposta agora. Posso tentar novamente."
        }
        if (!success) {
            brainCooldownUntil = SystemClock.elapsedRealtime() + 8_000L
        }

        conversationUntil = 0L
        wakeUntil = 0L
        uiHandler.post {
            if (!running || generation != requestGeneration) return@post
            if (isDeviceLocked()) {
                speak("Desbloqueie o Android para continuar.", true)
                return@post
            }
            updateNotification(answer)
            speak(answer, true)
        }
    }

    private fun failIsolatedBrain(requestId: Long, message: String) {
        if (requestId != activeBrainRequestId) return
        val generation = activeBrainGeneration
        val pid = activeBrainPid

        brainHealthToken++
        brainBusy = false
        activeBrainRequestId = 0L
        activeBrainPid = 0
        brainCooldownUntil = SystemClock.elapsedRealtime() + 8_000L

        try { stopService(Intent(this, GamaBrainProcessService::class.java)) } catch (_: Exception) {}
        if (pid > 0 && pid != android.os.Process.myPid()) {
            try { GamaProcessSafety.detachFailedBrain(pid) } catch (_: Exception) {}
        }

        if (!running || generation != requestGeneration) return
        updateNotification(message)
        speak(message, true)
    }

    private fun execute(raw: String): String {
        val c = norm(raw)
        val compact = c.replace(" ", "")

        SmartFeatures.localAction(this, c, raw)?.let { return it }
        DeviceExtras.answer(this, c, raw)?.let { return it }
        return when {
            isUnlockCommand(c) ->
                requestSecureUnlockReply()

            isLockCommand(c) -> lockScreenReply()

            isMessageReadCommand(c) -> messagesReply(c)

            c in setOf("diagnostico", "diagnóstico", "ultimo erro", "último erro", "qual foi o ultimo erro", "qual foi o último erro") -> crashDiagnosticReply()

            isTimeQuestion(c) -> currentTimeReply()
            c.contains("bateria") -> {
                val level = (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (level in 0..100) {
                    val advice = GamaPersona.batteryAdvice(level)
                    "A bateria está em $level por cento." + if (advice.isBlank()) "" else " $advice"
                } else "Não consegui ler a bateria."
            }

            c == "camera" ||
            c == "abrir camera" ||
            c == "abra camera" ||
            c == "abre camera" -> try {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Abrindo a câmera."
            } catch (_: Exception) {
                "Não consegui abrir a câmera."
            }

            c == "configuracoes" ||
            c == "ajustes" ||
            c == "settings" ||
            c == "abrir configuracoes" ||
            c == "abra configuracoes" ||
            c == "abrir ajustes" ||
            c == "abra ajustes" -> try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Abrindo as configurações."
            } catch (_: Exception) {
                "Não consegui abrir as configurações."
            }

            c.contains("tela inicial") || c == "inicio" -> try {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Voltando para a tela inicial."
            } catch (_: Exception) {
                "Não consegui voltar para a tela inicial."
            }

            c.contains("aument") && c.contains("volume") -> {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Aumentei o volume."
            }

            (c.contains("diminu") || c.contains("abaix")) && c.contains("volume") -> {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Diminuí o volume."
            }

            c.contains("silenci") && c.contains("volume") -> {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Volume silenciado."
            }

            c.contains("pause") || c.contains("pausar") -> {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "Música pausada."
            }

            c.contains("continue") || c.contains("retome") || c.contains("reprodu") -> {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                "Continuando."
            }

            c.contains("proxima") && (c.contains("musica") || c.contains("faixa")) -> {
                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "Próxima música."
            }

            c.contains("anterior") && (c.contains("musica") || c.contains("faixa")) -> {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Música anterior."
            }

            c.contains("lanterna") && c.contains("lig") && !c.contains("deslig") -> {
                if (torch(true)) "Lanterna ligada." else "Não encontrei uma lanterna disponível."
            }

            c.contains("lanterna") && (c.contains("deslig") || c.contains("apague")) -> {
                if (torch(false)) "Lanterna desligada." else "Não encontrei uma lanterna disponível."
            }

            c.startsWith("pesquise na wikipedia ") ||
            c.startsWith("procure na wikipedia ") ||
            c.startsWith("pesquisar na wikipedia ") ||
            c.startsWith("wikipedia ") ||
            c.startsWith("o que a wikipedia diz sobre ") -> {
                wikipediaReplyFast(extractWikipediaQuery(c))
            }

            isOpenAppCommand(c) -> {
                val query = extractAppQuery(c)
                openByLabel(query)
            }

            else -> openBareAppIfConfident(c) ?: "Não reconheci esse comando local."
        }
    }

    private fun isLockCommand(c: String): Boolean =
        GamaCriticalCommandPolicy.isLockScreen(c)

    private fun isUnlockCommand(c: String): Boolean {
        val n = norm(c)
        return (
            (n.contains("desbloque") || n.contains("destrave")) &&
            (n.contains("tela") || n.contains("celular") || n.contains("telefone") || n.contains("aparelho"))
        ) || n in setOf("desbloquear", "destravar", "desbloqueie", "destrave")
    }

    private fun requestSecureUnlockReply(): String {
        val manager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!manager.isKeyguardLocked) return "O celular já está desbloqueado."
        unlockPending = true
        val intent = Intent(this, LockScreenActivity::class.java).putExtra(EXTRA_REQUEST_SECURE_UNLOCK, true)
        launchSafely(intent)
        updateNotification("Toque em Desbloquear para continuar seu pedido.")
        return if (GamaApplication.foreground || SystemAssistant.current != null)
            "Confirme o desbloqueio na tela do Android. Vou continuar seu pedido depois."
        else "Toque em Desbloquear na notificação do Gama e confirme no Android. Seu pedido ficou guardado."
    }

    private fun lockScreenReply(): String {
        if (isDeviceLocked()) return "A tela já está bloqueada."

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, GamaDeviceAdminReceiver::class.java)
        val accessibilityCanLock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && GamaScreenContextService.isConnected()
        val adminCanLock = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)

        if (!accessibilityCanLock && !adminCanLock) {
            pendingLockAfterAccessibility = true
            if (GamaScreenContextService.openSettings(this)) {
                return "Ative Gama Contexto da Tela uma vez. Assim que a acessibilidade ficar ativa, eu continuo e bloqueio a tela."
            }
            val setup = Intent(this, MainActivity::class.java)
                .setAction("com.gama.assistant.ACTIVATE_DEVICE_ADMIN")
            launchSafely(setup)
            return "Preciso de uma autorização do Android para bloquear a tela. Abri a configuração."
        }

        requestGeneration++
        finishCommandListening()
        if (!zevronPlanRunning) {
            conversation.close()
            GamaSupervisor.onConversationClosed()
            conversationIdleToken++
            conversationLastActivityAt = 0L
        }
        conversationUntil = 0L
        wakeUntil = 0L
        armCommandWindowAfterTts = false
        queuedWakeCommand = null
        pendingSuggestedCommand = null
        pendingSuggestedAt = 0L
        pendingLockAfterSpeech = true
        return "Certo. Vou bloquear a tela."
    }

    private fun performDeviceLockNow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                GamaScreenContextService.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                updateNotification("Tela bloqueada • diga Gama quando precisar.")
                return
            }
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, GamaDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                updateNotification("Tela bloqueada • diga Gama quando precisar.")
                dpm.lockNow()
            } else {
                updateNotification("Ative Gama Contexto da Tela ou a permissão de bloqueio para usar este comando.")
            }
        } catch (_: Exception) {
            updateNotification("O Android recusou o bloqueio. Revise a acessibilidade ou a permissão de administrador do Gama.")
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return try {
            val component = ComponentName(this, GamaNotificationListener::class.java)
            val enabled = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()

            enabled.split(":").any {
                it.equals(component.flattenToString(), ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }


    private fun sendWhatsAppReliableAsync(target: String, message: String) {
        pendingWhatsAppTarget = null
        pendingWhatsAppAt = 0L
        val cleanMessage = message.trim()
        if (target.isBlank() || cleanMessage.isBlank()) {
            speak("Diga para quem e qual mensagem devo enviar.", true)
            return
        }

        when (GamaNotificationListener.replyToWhatsApp(target, cleanMessage)) {
            GamaReplyStatus.SENT -> {
                val result = "Mensagem enviada para $target pelo WhatsApp."
                GamaTaskMemory.rememberResult(this, result)
                updateNotification(result)
                speak(result, true)
                return
            }
            else -> Unit
        }

        if (!GamaScreenContextService.isConnected()) {
            pendingWhatsAppAccessibility = WhatsAppSendRequest(target, cleanMessage)
            if (GamaScreenContextService.openSettings(this)) {
                updateNotification("Ative Gama Contexto da Tela • o envio continuará automaticamente")
                speak("Ative Gama Contexto da Tela uma vez. Assim que ficar ativo, eu continuo o envio automaticamente.", true)
                return
            }
        }

        val automationRequest = GamaWhatsAppSendParser.Request(target, cleanMessage)
        updateNotification("Enviando mensagem para $target...")
        GamaWhatsAppAutomation.start(this, automationRequest) { result ->
            uiHandler.post {
                GamaTaskMemory.rememberResult(this, result)
                updateNotification(result)
                speak(result, true)
            }
        }
    }

    fun resumePendingAccessibilityActions() {
        postAudio {
            val lock = pendingLockAfterAccessibility
            pendingLockAfterAccessibility = false
            if (lock && !isDeviceLocked()) {
                pendingLockAfterSpeech = true
                speak("Controle do Android ativado. Vou bloquear a tela agora.", true)
            }

            val request = pendingWhatsAppAccessibility
            pendingWhatsAppAccessibility = null
            if (request != null && !request.message.isNullOrBlank()) {
                sendWhatsAppReliableAsync(request.target, request.message)
            }
        }
    }

    private fun handleWhatsAppRequest(request: WhatsAppSendRequest): String {
        if (request.message.isNullOrBlank()) {
            pendingWhatsAppTarget = request.target
            pendingWhatsAppAt = SystemClock.elapsedRealtime()
            return "Qual mensagem devo enviar para ${request.target}?"
        }
        return sendWhatsAppNow(request.target, request.message)
    }

    private fun sendWhatsAppNow(target: String, message: String): String {
        pendingWhatsAppTarget = null
        pendingWhatsAppAt = 0L
        when (GamaNotificationListener.replyToWhatsApp(target, message)) {
            GamaReplyStatus.SENT ->
                return "Mensagem enviada para $target pelo WhatsApp."
            else -> Unit
        }
        if (!WhatsAppMessaging.hasContactsPermission(this)) {
            pendingWhatsAppPermission = WhatsAppSendRequest(target, message)
            val intent = Intent(this, MainActivity::class.java)
                .setAction("com.gama.assistant.REQUEST_CONTACTS")
            launchSafely(intent)
            return "Preciso de acesso aos contatos para localizar $target. Vou abrir a autorização."
        }
        return WhatsAppMessaging.openPreparedChat(this, target, message).reply
    }

    fun completeWhatsAppContactsPermission(granted: Boolean) { postAudio {
        val request = pendingWhatsAppPermission
        pendingWhatsAppPermission = null
        if (request == null) return@postAudio
        val reply = if (granted) sendWhatsAppNow(request.target, request.message.orEmpty())
            else "Sem acesso aos contatos, não consigo localizar ${request.target} pelo nome."
        updateNotification(reply)
        speakCommandReply(reply, true)
    } }

    private fun messagesReply(raw: String): String {
        if (!MessageCommandRouter.isNotificationRequest(raw)) {
            return GamaMessageCenter.readUnread(this)
        }

        if (!GamaNotificationCenter.hasAccess(this)) {
            pendingNotificationReadAt = SystemClock.elapsedRealtime()
            val intent = Intent(this, MainActivity::class.java)
                .setAction("com.gama.assistant.REQUEST_NOTIFICATION_ACCESS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            launchSafely(intent)
            return "Ainda falta o acesso especial às notificações. Abri a configuração certa. Depois de ativar Gama, volte; eu retomo este pedido automaticamente."
        }

        pendingNotificationReadAt = 0L
        return GamaNotificationCenter.readActive(this)
    }

    /** Resume the exact notification-reading goal after Android grants access. */
    fun resumePendingNotificationRead() {
        postAudio {
            val requestedAt = pendingNotificationReadAt
            if (requestedAt <= 0L) return@postAudio
            if (SystemClock.elapsedRealtime() - requestedAt > 3L * 60L * 1000L) {
                pendingNotificationReadAt = 0L
                return@postAudio
            }
            if (!GamaNotificationCenter.hasAccess(this)) return@postAudio

            pendingNotificationReadAt = 0L
            val reply = GamaNotificationCenter.readActive(this)
            updateNotification(reply)
            speak(reply, closeAfter = false)
        }
    }

    private fun crashDiagnosticReply(): String {
    val diagnosticIntent = android.content.Intent(this, GamaDiagnosticActivity::class.java)
        .addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    return if (runCatching { startActivity(diagnosticIntent) }.isSuccess) {
        "Abri o último diagnóstico na tela."
    } else {
        "Não consegui abrir o diagnóstico agora."
    }
}

    private fun currentTimeReply(): String {
        return try {
            val cal = java.util.Calendar.getInstance()
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            val minute = cal.get(java.util.Calendar.MINUTE).coerceIn(0, 59)
            when {
                hour == 1 && minute == 0 -> "Agora é 1 hora em ponto."
                hour == 1 -> "Agora é 1 hora e $minute ${if (minute == 1) "minuto" else "minutos"}."
                minute == 0 -> "Agora são $hour horas em ponto."
                else -> "Agora são $hour horas e $minute ${if (minute == 1) "minuto" else "minutos"}."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao consultar relógio do Android", e)
            "Não consegui consultar o relógio do Android agora."
        }
    }

    private fun openPackage(pkg: String, label: String): String {
        return try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?: return "Não encontrei o $label neste celular."
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            "$label aberto."
        } catch (_: Exception) {
            "Não consegui abrir o $label."
        }
    }

    private fun isWikipediaCommand(command: String): Boolean {
        val c = norm(command)

        return c.startsWith("pesquise na wikipedia ") ||
            c.startsWith("procure na wikipedia ") ||
            c.startsWith("pesquisar na wikipedia ") ||
            c.startsWith("wikipedia ") ||
            c.startsWith("o que a wikipedia diz sobre ") ||
            c.startsWith("pesquise ") ||
            c.startsWith("pesquisar ") ||
            c.startsWith("procure ") ||
            c.startsWith("procurar ") ||
            c.startsWith("faca uma pesquisa ") ||
            c.startsWith("faca uma pesquisa sobre ") ||
            c.startsWith("quero pesquisar ") ||
            c.startsWith("pesquisa sobre ")
    }

    private fun extractWikipediaQuery(command: String): String {
        var q = norm(command)

        val prefixes = listOf(
            "o que a wikipedia diz sobre ",
            "pesquise na wikipedia ",
            "procure na wikipedia ",
            "pesquisar na wikipedia ",
            "wikipedia ",
            "faca uma pesquisa sobre ",
            "faca uma pesquisa ",
            "quero pesquisar ",
            "pesquisa sobre ",
            "pesquisar ",
            "pesquise ",
            "procurar ",
            "procure "
        )

        for (prefix in prefixes) {
            if (q.startsWith(prefix)) {
                q = q.removePrefix(prefix).trim()
                break
            }
        }

        return q
    }

    private fun researchWikipediaAsync(
        query: String,
        creatorMode: Boolean
    ) {
        if (query.isBlank()) {
            speak("Diga o assunto que você quer pesquisar.", true)
            return
        }

        if (researchBusy) {
            speak("Já estou concluindo a pesquisa anterior.", true)
            return
        }

        val generation = requestGeneration
        researchBusy = true
    updateNotification("Pesquisando: $query")
    uiHandler.postDelayed({
        if (running && researchBusy && generation == requestGeneration) {
            researchBusy = false
            requestGeneration++
            speak("A pesquisa demorou demais. Tente novamente em instantes.", true)
        }
    }, 35000L)
        setConversationVisual(GamaEnergyView.Mode.THINKING)

        thread(name = "gama-research") {
            val answer = wikipediaReplyFast(query)

            researchBusy = false

            uiHandler.post {
                if (generation != requestGeneration || !running) return@post
                if (isDeviceLocked()) { speak("Desbloqueie o Android para continuar.", true); return@post }
                updateNotification(answer)
                speak(answer, true)
            }
        }
    }

    private fun wikipediaReplyFast(
        query: String
    ): String {
        if (query.isBlank()) {
            return "Diga o assunto que você quer pesquisar."
        }

        var connection: HttpURLConnection? = null

        return try {
            val encoded =
                URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            val endpoint =
                "https://pt.wikipedia.org/w/api.php" +
                "?action=query" +
                "&generator=search" +
                "&gsrsearch=$encoded" +
                "&gsrlimit=1" +
                "&prop=extracts" +
                "&exintro=1" +
                "&explaintext=1" +
                "&redirects=1" +
                "&format=json"

            connection =
                URL(endpoint).openConnection()
                    as HttpURLConnection

            connection.connectTimeout = 2200
            connection.readTimeout = 3200
            connection.requestMethod = "GET"
            connection.useCaches = true
            connection.setRequestProperty(
                "User-Agent",
                "Gama-Android/1.0"
            )

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val root = JSONObject(response)
            val pages =
                root.optJSONObject("query")
                    ?.optJSONObject("pages")

            if (pages == null || pages.length() == 0) {
                return "Não encontrei esse assunto na Wikipedia."
            }

            val keys = pages.keys()

            if (!keys.hasNext()) {
                return "Não encontrei esse assunto na Wikipedia."
            }

            val page =
                pages.optJSONObject(keys.next())
                    ?: return "Não encontrei esse assunto na Wikipedia."

            val title =
                page.optString("title")
                    .trim()

            val extract =
                page.optString("extract")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace("\\s+".toRegex(), " ")
                    .trim()

            if (extract.isBlank()) {
                "Encontrei o artigo, mas ele não trouxe um resumo."
            } else {
                val short =
                    if (extract.length > 300) {
                        extract.take(300)
                            .substringBeforeLast(" ")
                            .trim() + "..."
                    } else {
                        extract
                    }

                if (title.isBlank()) {
                    short
                } else {
                    "$title. $short"
                }
            }
        } catch (_: Exception) {
            "A pesquisa demorou mais do que o esperado. Pode repetir o assunto que eu tento novamente."
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }


    private data class InstalledApp(
        val label: String,
        val packageName: String,
        val launchIntent: Intent
    )

    private fun isOpenAppCommand(command: String): Boolean {
        if (CommandRouter.looksLikeOpenRequest(command)) return true
        val prefixes = listOf(
            "abra ", "abrir ", "abre ",
            "abra o ", "abrir o ", "abre o ",
            "abra a ", "abrir a ", "abre a ",
            "abra o app ", "abrir o app ", "abre o app ",
            "abra o aplicativo ", "abrir o aplicativo ", "abre o aplicativo ",
            "inicie ", "iniciar ", "inicia ",
            "execute ", "executar ",
            "rode ", "rodar ",
            "entre no ", "entra no ", "entrar no ",
            "entre na ", "entra na ", "entrar na ",
            "va para ", "vai para ", "ir para ",
            "acesse ", "acessar "
        )
        return prefixes.any { command.startsWith(it) }
    }

    private fun extractAppQuery(command: String): String {
        val prefixes = listOf(
            "abra o aplicativo ", "abrir o aplicativo ", "abre o aplicativo ",
            "abra o app ", "abrir o app ", "abre o app ",
            "abrir o ", "abra o ", "abre o ",
            "abrir a ", "abra a ", "abre a ",
            "iniciar ", "inicie ", "inicia ",
            "executar ", "execute ",
            "rodar ", "rode ",
            "entrar no ", "entre no ", "entra no ",
            "entrar na ", "entre na ", "entra na ",
            "ir para ", "va para ", "vai para ",
            "acessar ", "acesse ",
            "abrir ", "abra ", "abre "
        )

        var q = command.trim()
        for (prefix in prefixes) {
            if (q.startsWith(prefix)) {
                q = q.removePrefix(prefix).trim()
                break
            }
        }

        return cleanAppQuery(q)
    }

    private fun cleanAppQuery(raw: String): String {
        var q = norm(raw)

        val starts = listOf(
            "aplicativo ", "app ", "o aplicativo ", "o app ",
            "o ", "a "
        )
        for (prefix in starts) {
            if (q.startsWith(prefix) && q.length > prefix.length + 1) {
                q = q.removePrefix(prefix).trim()
                break
            }
        }

        val endings = listOf(
            " por favor", " pra mim", " para mim",
            " ai", " agora", " no meu celular", " no celular"
        )
        var changed = true
        while (changed) {
            changed = false
            for (ending in endings) {
                if (q.endsWith(ending)) {
                    q = q.removeSuffix(ending).trim()
                    changed = true
                }
            }
        }

        return q
    }

    private fun canonicalAppQuery(raw: String): String {
        val q = cleanAppQuery(raw)
        val compact = q.replace(" ", "")

        return when {
            compact in setOf(
                "spotify", "spotfy", "sportfy", "sportify",
                "espotify", "ispotify", "spotifi"
            ) -> "spotify"

            compact in setOf(
                "whatsapp", "whatsap", "watsapp", "watsap",
                "uatsapp", "uatsap", "whatsup", "wats", "whats", "zap", "zapzap"
            ) -> "whatsapp"

            compact in setOf(
                "instagram", "insta", "instagran", "istagram"
            ) -> "instagram"

            compact in setOf(
                "youtube", "iutube", "yutube", "yutub"
            ) -> "youtube"

            compact in setOf(
                "tiktok", "tictoque", "tictok"
            ) -> "tiktok"

            compact in setOf(
                "playstore", "lojaplay", "googleplay"
            ) -> "play store"

            else -> q
        }
    }

    private fun compactAppText(raw: String): String =
        canonicalAppQuery(raw).replace(" ", "")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1

            for (j in b.indices) {
                val insert = current[j] + 1
                val delete = previous[j + 1] + 1
                val replace =
                    previous[j] + if (a[i] == b[j]) 0 else 1

                current[j + 1] =
                    minOf(insert, delete, replace)
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }

    private fun installedApps(): List<InstalledApp> {
        val now = SystemClock.elapsedRealtime()
        installedAppsCache?.let { cached ->
            if (now - installedAppsCacheAt <= 5L * 60L * 1000L) return cached
        }
        val result = linkedMapOf<String, InstalledApp>()

        // Primeiro pegamos todas as Activities que aparecem no launcher.
        try {
            val launcherQuery =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)

            val activities =
                packageManager.queryIntentActivities(
                    launcherQuery,
                    PackageManager.MATCH_ALL
                )

            for (info in activities) {
                val activity = info.activityInfo ?: continue
                if (!activity.enabled) continue

                val pkg = activity.packageName
                val label = try {
                    info.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast(".")
                }

                val explicit =
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(
                            ComponentName(
                                pkg,
                                activity.name
                            )
                        )
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        )

                result[pkg] =
                    InstalledApp(
                        label = label,
                        packageName = pkg,
                        launchIntent = explicit
                    )
            }
        } catch (_: Exception) {}

        // Depois varremos TODOS os pacotes instalados visíveis ao Android.
        // QUERY_ALL_PACKAGES está no Manifest. Qualquer pacote que exponha
        // uma Activity de inicialização entra no índice automaticamente.
        try {
            @Suppress("DEPRECATION")
            val installed =
                packageManager.getInstalledApplications(
                    PackageManager.GET_META_DATA
                )

            for (app in installed) {
                if (!app.enabled) continue

                val launch =
                    try {
                        packageManager.getLaunchIntentForPackage(
                            app.packageName
                        )
                    } catch (_: Exception) {
                        null
                    } ?: continue

                val label =
                    try {
                        packageManager
                            .getApplicationLabel(app)
                            .toString()
                    } catch (_: Exception) {
                        app.packageName.substringAfterLast(".")
                    }

                if (!result.containsKey(app.packageName)) {
                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )

                    result[app.packageName] =
                        InstalledApp(
                            label = label,
                            packageName = app.packageName,
                            launchIntent = launch
                        )
                }
            }
        } catch (_: Exception) {}

        val finalList = result.values
            .sortedBy { norm(it.label) }
        installedAppsCache = finalList
        installedAppsCacheAt = now
        return finalList
    }

    private fun appDistance(
        query: String,
        app: InstalledApp
    ): Int {
        val q = compactAppText(query)
        val label =
            norm(app.label).replace(" ", "")
        val packageTail =
            norm(
                app.packageName
                    .substringAfterLast(".")
            ).replace(" ", "")

        if (q == label || q == packageTail) return 0

        if (
            label.contains(q) ||
            q.contains(label) ||
            packageTail.contains(q)
        ) {
            return 1
        }

        val labelDistance =
            levenshtein(q, label)

        val packageDistance =
            levenshtein(q, packageTail)

        return minOf(
            labelDistance,
            packageDistance
        )
    }

    private fun isGoodAppMatch(
        query: String,
        app: InstalledApp
    ): Boolean {
        val q = compactAppText(query)
        if (q.length < 2) return false

        val label =
            norm(app.label).replace(" ", "")

        if (
            q == label ||
            label.contains(q) ||
            q.contains(label)
        ) {
            return true
        }

        val distance =
            appDistance(query, app)

        val allowed =
            when {
                q.length <= 4 -> 1
                q.length <= 7 -> 2
                q.length <= 11 -> 3
                else -> maxOf(3, q.length / 3)
            }

        return distance <= allowed
    }

    private fun openInstalledApp(
        app: InstalledApp
    ): String {
        return try {
            val launch =
                Intent(app.launchIntent)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )

            startActivity(launch)
            "Abrindo ${app.label}."
        } catch (_: Exception) {
            try {
                val fallback =
                    packageManager
                        .getLaunchIntentForPackage(
                            app.packageName
                        )
                        ?: return "O ${app.label} está instalado, mas o Android não oferece uma tela que eu possa abrir diretamente."

                fallback.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                startActivity(fallback)
                "Abrindo ${app.label}."
            } catch (_: Exception) {
                "O ${app.label} está instalado, mas não consegui abrir a tela dele."
            }
        }
    }

    private fun openKnownApp(query: String): String? {
        val cleaned = canonicalAppQuery(query)
        val candidates: List<Pair<String, String>> = when (cleaned) {
            "whatsapp" -> listOf(
                "com.whatsapp" to "WhatsApp",
                "com.whatsapp.w4b" to "WhatsApp Business"
            )
            "spotify" -> listOf("com.spotify.music" to "Spotify")
            "instagram" -> listOf("com.instagram.android" to "Instagram")
            "youtube" -> listOf("com.google.android.youtube" to "YouTube")
            "tiktok" -> listOf("com.zhiliaoapp.musically" to "TikTok")
            else -> return null
        }

        for ((pkg, label) in candidates) {
            val launch = try {
                packageManager.getLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(pkg)
                        .takeIf { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
            } catch (_: Exception) { null }
            if (launch != null) {
                return try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val systemReply = launchSafely(launch)
                    if (deferredLaunch) systemReply else "Abrindo $label."
                } catch (_: Exception) {
                    "Encontrei o $label, mas o Android não deixou eu abrir agora."
                }
            }
        }

        return if (cleaned == "whatsapp") {
            "Não encontrei o WhatsApp instalado neste celular."
        } else {
            null
        }
    }

    private fun openByLabel(
        query: String
    ): String {
        return try {
            val cleaned = canonicalAppQuery(query)

            openKnownApp(cleaned)?.let { return it }

            if (cleaned.isBlank()) {
                return "Diga o nome do aplicativo que você quer abrir."
            }

            val apps = installedApps()
            if (apps.isEmpty()) {
                return "Não consegui acessar a lista de aplicativos instalados."
            }

            val ranked = apps
                .map { Pair(it, appDistance(cleaned, it)) }
                .sortedWith(compareBy<Pair<InstalledApp, Int>> { it.second }.thenBy { it.first.label.length })

            val best = ranked.firstOrNull()?.first
                ?: return "Não encontrei aplicativos instalados."

            if (!isGoodAppMatch(cleaned, best)) {
                return "Não encontrei um aplicativo chamado $cleaned neste celular."
            }

            openInstalledApp(best)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao procurar ou abrir aplicativo: $query", e)
            "Não consegui consultar os aplicativos agora. Tente novamente."
        }
    }

    private fun openBareAppIfConfident(
        command: String
    ): String? {
        val q =
            canonicalAppQuery(command)

        openKnownApp(q)?.let { return it }

        // Uma frase longa provavelmente é uma pergunta,
        // não o nome de um aplicativo.
        if (
            q.isBlank() ||
            q.length > 32 ||
            q.split(" ").size > 4 ||
            q.contains(" o que ") ||
            q.startsWith("o que ") ||
            q.startsWith("como ") ||
            q.startsWith("porque ") ||
            q.startsWith("por que ") ||
            q.startsWith("qual ") ||
            q.startsWith("quem ") ||
            q.startsWith("quando ") ||
            q.startsWith("onde ") ||
            q.startsWith("pesquise ") ||
            q.startsWith("procure ")
        ) {
            return null
        }

        val apps =
            installedApps()

        val ranked =
            apps
                .map {
                    Pair(
                        it,
                        appDistance(q, it)
                    )
                }
                .sortedBy {
                    it.second
                }

        val best =
            ranked.firstOrNull()
                ?: return null

        if (!isGoodAppMatch(q, best.first)) {
            return null
        }

        // Para comando sem "abra", exigimos confiança maior.
        val qCompact =
            compactAppText(q)

        val labelCompact =
            norm(best.first.label)
                .replace(" ", "")

        val distance =
            appDistance(q, best.first)

        val veryConfident =
            qCompact == labelCompact ||
            labelCompact.contains(qCompact) ||
            qCompact.contains(labelCompact) ||
            distance <= when {
                qCompact.length <= 4 -> 0
                qCompact.length <= 8 -> 1
                else -> 2
            }

        return if (veryConfident) {
            openInstalledApp(best.first)
        } else {
            null
        }
    }

    private fun rememberSuggestedAction(reply: String) {
        val action = ActionFollowUp.suggestedCommand(reply)
        pendingSuggestedCommand = action
        pendingSuggestedAt = if (action == null) 0L else SystemClock.elapsedRealtime()
    }

    private fun audio() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun mediaKey(code: Int) {
        audio().dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio().dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun torch(on: Boolean): Boolean {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun speak(gamaRawSpeech: String, closeAfter: Boolean = false, record: Boolean = true) {
        // GAMA89_REPLY_GUARD
        val text = GamaReplyGuard.sanitize(gamaRawSpeech)

    if (!running) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
        uiHandler.post { speak(text, closeAfter, record) }
        return
    }
    if ((speaking || ttsSpeaking) && activeUtterance != null) {
        queuedSpeechText = text
        queuedSpeechCloseAfter = closeAfter
        queuedSpeechRecord = record
        return
    }
    markResponseStarted()
    if (record) try { rememberSuggestedAction(text) } catch (e: Exception) { Log.w(TAG, "Falha ao lembrar sugestão", e) }
    val displayText = try {
        if (record) GamaPersona.address(text, OwnerIdentity.treatment(this), OwnerIdentity.name(this)) else text
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao formatar resposta", e)
        text.trim()
    }
    if (displayText.isBlank()) return
    try {
        if (record && !isDeviceLocked()) AssistantRuntime.add(false, displayText)
        if (record && !isDeviceLocked()) conversation.gamaSaid(displayText)
        if (record && !isDeviceLocked()) GamaContinuityMemory.recordAssistant(this, displayText)
        if (record) ZevronContextResolver.rememberReply(displayText)
        if (record && zevronPlanRunning) {
            GamaMissionStore.rememberResult(this, displayText)
            lastPlanStepMayContinue = GamaActionVerifier.mayContinuePlan(displayText)
            if (currentPlanStep.isNotBlank()) {
                GamaContinuityMemory.recordAction(this, currentPlanStep, displayText)
            }
        }
    } catch (e: Exception) { Log.w(TAG, "Falha ao registrar resposta", e) }
    try { sendBroadcast(Intent(LockScreenActivity.ACTION_SPEAKING).setPackage(packageName)) }
    catch (e: Exception) { Log.w(TAG, "Falha no broadcast de fala", e) }
    try {
        if (isDeviceLocked()) {
            sendBroadcast(
                Intent("com.gama.assistant.ACTION_UI_PULSE").setPackage(packageName)
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Falha no pulso visual da lockscreen", e)
    }
    closeUiAfterSpeech = closeAfter && !isDeviceLocked() && !conversation.active
    setConversationVisual(GamaEnergyView.Mode.SPEAKING)
    if (!ttsReady) {
        if (ttsFailed) {
            val id = "failed-${SystemClock.elapsedRealtimeNanos()}"
            activeUtterance = id
            finishTts(id)
        } else {
            pendingSpeech = displayText
            pendingCloseAfterSpeech = closeAfter
        }
        return
    }
    ttsSpeaking = true
    speaking = true
    val id = "gama-${SystemClock.elapsedRealtimeNanos()}"
    activeUtterance = id
    activeSpeechText = displayText
    ttsRetryCount = 0
    val result = safeTtsSpeak(displayText, id)
    if (result == null || result == TextToSpeech.ERROR) finishTts(id)
    uiHandler.postDelayed(
        { if (activeUtterance == id) finishTts(id) },
        maxOf(15000L, displayText.length * 180L)
    )
}

    private fun isTimeQuestion(raw: String): Boolean {
        val c = norm(raw)

        val asksClock = c.contains("que horas") ||
            c.contains("que hora") ||
            c.contains("que ora") ||
            c.contains("qual a hora") ||
            c.contains("qual hora") ||
            c.contains("qual horario") ||
            c.contains("qual o horario") ||
            c.contains("horas sao") ||
            c.contains("hora agora") ||
            c.contains("horas agora") ||
            c.contains("horario agora") ||
            c.contains("me diga as horas") ||
            c.contains("diga as horas") ||
            c == "as horas" ||
            c == "hora" ||
            c == "horas" ||
            c == "horario"
        if (!asksClock) return false

        // "Que horas começa a votação amanhã?" asks for the schedule of an
        // external event, not the phone clock. Let the online/current-data route
        // answer it instead of replying with the current time.
        val eventQuestion = listOf(
            "comeca", "inicia", "abre", "fecha", "termina", "acontece", "sera",
            "eleicao", "eleicoes", "votacao", "jogo", "evento", "show", "prova",
            "consulta", "reuniao", "voo", "onibus", "filme", "amanha", "ontem"
        ).any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(c) }
        return !eventQuestion
    }

    private fun shouldDecodeAudio(buffer: ByteArray, read: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val metrics = lastAudioMetrics
        val voiceEvidence = GamaRecognitionPolicy.hasVoiceEvidence(metrics)
        val sessionListening = conversation.active || waitingForCommandStart ||
            commandSpeechStarted || transcription != null ||
            ownerEnrollmentMode || ownerRetryPending

        if (voiceEvidence) {
            voicedBlocks++
            lastVoiceEvidenceAt = now
            lastSpeechAt = now
            speechHoldUntil = now + 1700L
            if (waitingForCommandStart && commandWindow.speech(now)) {
                markCommandSpeechStarted()
            }
        } else {
            voicedBlocks = 0
        }

        if (noisyAudioBlocks >= 18 && now - lastNoiseHintAt > 45_000L &&
            !speaking && !ttsSpeaking && conversation.active) {
            lastNoiseHintAt = now
            updateNotification("Ambiente ruidoso • isolando sua voz")
        }

        // Critical reliability rule: once a conversation is open, never starve
        // Vosk because a heuristic VAD disliked the frame. Wind/crowd metrics are
        // still used for safety/confirmation, but the decoder receives the whole
        // cleaned utterance so beginnings and quiet syllables are not chopped.
        return GamaRecognitionPolicy.shouldFeedDecoder(
            sessionListening = sessionListening,
            decoderActive = decoderActive,
            inSpeechHold = now < speechHoldUntil,
            voiceEvidence = voiceEvidence,
        )
    }

    private fun averageRecognitionConfidence(
        json: JSONObject
    ): Double {
        val words =
            json.optJSONArray("result")
                ?: return 1.0

        if (words.length() == 0) {
            return 1.0
        }

        var total = 0.0
        var count = 0

        for (i in 0 until words.length()) {
            val item =
                words.optJSONObject(i)
                    ?: continue

            val confidence =
                item.optDouble(
                    "conf",
                    -1.0
                )

            if (confidence >= 0.0) {
                total += confidence
                count++
            }
        }

        return if (count > 0) {
            total / count
        } else {
            1.0
        }
    }

    private fun norm(s: String): String {
        var n = Normalizer.normalize(s.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
        n = n.replace("\\p{Mn}+".toRegex(), "")
        n = n.replace("[^a-z0-9 ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
        n = n.replace(Regex("\\b(?:zap\\s+zap|zapzap|zape|zap)\\b"), "whatsapp")
        n = n.replace(Regex("\\b(?:whats\\s+app|what\\s+s\\s+up|wats\\s+app|uats\\s+app|whatsap|watsapp|watsap|uatsapp|uatsap)\\b"), "whatsapp")
        return n.replace("\\s+".toRegex(), " ").trim()
    }

    fun deleteEnrolledVoice() { postAudio {
        clearOwnerVoiceRetry()
        ownerEnrollmentMode = false
        ownerEnrollmentVectors.clear()
        cachedReferences = null
        ownerVoicePrefs().edit().clear().apply()
        OwnerIdentity.setVoiceOptIn(this, false)
    } }

    private fun ownerVoicePrefs() =
        getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)

    private fun extractSpeakerVector(json: JSONObject): FloatArray? {
        val arr = json.optJSONArray("spk") ?: return null
        if (arr.length() < 16) return null
        return FloatArray(arr.length()) { index ->
            arr.optDouble(index, 0.0).toFloat()
        }
    }

    private fun speakerFrames(json: JSONObject): Int =
        json.optInt("spk_frames", 0)

    private fun normalizeSpeakerVector(vector: FloatArray): FloatArray? = VoicePolicy.unit(vector)

    private fun loadOwnerVector(): FloatArray? {
        val raw = ownerVoicePrefs().getString(OWNER_VECTOR_KEY, null) ?: return null
        val values = raw.split(',').mapNotNull { it.toFloatOrNull() }
        if (values.size < 16) return null
        return normalizeSpeakerVector(values.toFloatArray())
    }

    private fun encodeVector(vector: FloatArray): String =
        vector.joinToString(",") { value ->
            String.format(Locale.US, "%.7f", value)
        }

    private fun saveOwnerProfile(
        centroid: FloatArray,
        samples: List<FloatArray>,
        frames: Int,
        threshold: Double
    ) {
        cachedReferences = samples.map { it.copyOf() }
        val sampleJson = JSONArray()
        for (sample in samples) {
            sampleJson.put(encodeVector(sample))
        }

        ownerVoicePrefs().edit()
            .putString(OWNER_VECTOR_KEY, encodeVector(centroid))
            .putString(OWNER_SAMPLES_KEY, sampleJson.toString())
            .putInt(OWNER_FRAMES_KEY, frames)
            .putFloat(OWNER_THRESHOLD_KEY, threshold.toFloat())
            .apply()
    }

    private fun loadOwnerSamples(): List<FloatArray> {
        cachedReferences?.let { return it }
        val raw = ownerVoicePrefs().getString(OWNER_SAMPLES_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<FloatArray>()
            for (i in 0 until array.length()) {
                val values = array.optString(i)
                    .split(',')
                    .mapNotNull { it.toFloatOrNull() }
                if (values.size >= 16) {
                    normalizeSpeakerVector(values.toFloatArray())?.let(result::add)
                }
            }
            result.also { cachedReferences = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ownerThreshold(): Double =
        ownerVoicePrefs()
            .getFloat(OWNER_THRESHOLD_KEY, OWNER_BASE_THRESHOLD.toFloat())
            .toDouble()
            .coerceIn(OWNER_MIN_THRESHOLD, OWNER_MAX_THRESHOLD)

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 0.0 || nb <= 0.0) return -1.0
        return dot / kotlin.math.sqrt(na * nb)
    }

    private fun profileCohesion(vectors: List<FloatArray>): Double {
        if (vectors.size < 2) return 1.0
        val similarities = mutableListOf<Double>()
        for (i in 0 until vectors.lastIndex) {
            for (j in i + 1 until vectors.size) {
                similarities += cosineSimilarity(vectors[i], vectors[j])
            }
        }
        if (similarities.isEmpty()) return 1.0
        return similarities.sorted()[similarities.size / 2]
    }

    private fun adaptiveOwnerThreshold(vectors: List<FloatArray>): Double = VoicePolicy.threshold(vectors)

    private fun assessOwnerVoice(json: JSONObject): OwnerVoiceAssessment {
        val refs = loadOwnerSamples().ifEmpty { listOfNotNull(loadOwnerVector()) }
        val result = VoicePolicy.assess(extractSpeakerVector(json), speakerFrames(json), refs)
        val decision = when (result.level) {
            VoicePolicy.Level.HIGH -> OwnerVoiceDecision.MATCH
            VoicePolicy.Level.MEDIUM -> OwnerVoiceDecision.RETRY
            VoicePolicy.Level.LOW -> OwnerVoiceDecision.REJECT
        }
        return OwnerVoiceAssessment(decision, result.score, result.threshold)
    }

    private fun isDeviceLocked(): Boolean = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

    private fun hasTrustedOwnerSession(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return !isDeviceLocked() && recentAndroidAuth > 0L && now - recentAndroidAuth <= OWNER_SESSION_MS
    }

    private fun isSafeWhileLocked(command: String): Boolean {
        val c = norm(command)
        if (DeviceExtras.known(c)) return DeviceExtras.safe(c)
        if (WhatsAppCommandParser.looksLikeSendCommand(c) || c.contains("mensage") || c.contains("notifica") || isOpenAppCommand(c) ||
            isWikipediaCommand(c) || c.contains("camera") || c.contains("configura")) return false
        return isUnlockCommand(c) || isTimeQuestion(c) || c.contains("bateria") ||
            c.contains("lanterna") || c.contains("volume") || c == "pause" || c == "pausar" ||
            c == "continue" || c == "retome" || c == "proxima musica" || c == "musica anterior" ||
            c == "proxima faixa" || c == "faixa anterior" ||
            c == "bloqueie a tela" || c == "bloquear tela"
    }

    private fun requestNoiseConfirmation(command: String) {
        pendingSuggestedCommand = command
        pendingSuggestedAt = SystemClock.elapsedRealtime()
        armCommandWindowAfterTts = true
        val kind = when {
            WhatsAppCommandParser.looksLikeSendCommand(norm(command)) -> "um envio pelo WhatsApp"
            AutomationCommandRouter.looksLikeAutomationCommand(norm(command)) -> "uma automação"
            else -> "essa ação"
        }
        updateNotification("Ambiente ruidoso • aguardando confirmação")
        speak("Ouvi $kind. Confirmo?", false)
    }

    private fun authorizeCommand(command: String, json: JSONObject, fromVoice: Boolean = false) {
        if (command.isBlank()) return
        val durableResolved = GamaReferenceMemory.resolve(this, command)
        val resolvedCommand = ZevronContextResolver.resolve(durableResolved)
        if (resolvedCommand.isBlank()) return
        if (isEndConversation(resolvedCommand)) {
            closeConversationForGoodbye()
            return
        }
        ZevronContextResolver.rememberCommand(resolvedCommand)
        GamaReferenceMemory.observe(this, resolvedCommand)
        val command = resolvedCommand
        val normalizedForFollowUp = norm(command)
        val pendingTarget = pendingWhatsAppTarget
        val pendingFresh = pendingTarget != null && pendingWhatsAppAt > 0L &&
            SystemClock.elapsedRealtime() - pendingWhatsAppAt <= 45000L
        if (pendingFresh && !WhatsAppCommandParser.looksLikeSendCommand(normalizedForFollowUp)) {
            if (normalizedForFollowUp in setOf("cancelar", "cancele", "esquece", "deixa pra la", "deixa para la")) {
                pendingWhatsAppTarget = null
                pendingWhatsAppAt = 0L
                speak("Certo. Cancelei a mensagem.", true)
                return
            }
            val definitelyAnotherCommand = isTimeQuestion(normalizedForFollowUp) || isLockCommand(normalizedForFollowUp) ||
                isOpenAppCommand(normalizedForFollowUp) || SmartFeatures.isAsync(normalizedForFollowUp) ||
                AutomationCommandRouter.looksLikeAutomationCommand(normalizedForFollowUp) ||
                DeviceExtras.known(normalizedForFollowUp)
            if (!definitelyAnotherCommand) {
                val body = WhatsAppCommandParser.followUpBody(command)
                pendingWhatsAppTarget = null
                pendingWhatsAppAt = 0L
                if (body.isNotBlank()) {
                    transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
                    transcription = null
                    finishCommandListening()
                    sendWhatsAppReliableAsync(pendingTarget!!, body)
                    return
                }
            } else {
                pendingWhatsAppTarget = null
                pendingWhatsAppAt = 0L
            }
        } else if (!pendingFresh) {
            pendingWhatsAppTarget = null
            pendingWhatsAppAt = 0L
        }
        if (handleAutomationVoiceCommand(command, normalizedForFollowUp)) return
        val pendingSuggestionFresh = pendingSuggestedCommand != null &&
            pendingSuggestedAt > 0L &&
            SystemClock.elapsedRealtime() - pendingSuggestedAt <= 120000L
        if (pendingSuggestionFresh && ActionFollowUp.isRejection(normalizedForFollowUp)) {
            pendingSuggestedCommand = null
            pendingSuggestedAt = 0L
            speak("Certo. Não vou executar.", true)
            return
        }
        if (ActionFollowUp.isConfirmation(normalizedForFollowUp)) {
            val now = SystemClock.elapsedRealtime()
            val suggested = pendingSuggestedCommand
                ?.takeIf { pendingSuggestedAt > 0L && now - pendingSuggestedAt <= 120000L }
            pendingSuggestedCommand = null
            pendingSuggestedAt = 0L
            if (suggested.isNullOrBlank()) {
                speak("Não tenho uma ação pendente para executar.", true)
                return
            }
            transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
            transcription = null
            finishCommandListening()
            pendingCommand.clear()
            requestGeneration++
            if (!isFastDeviceCommand(norm(suggested))) armResponseDeadline() else markResponseStarted()
            executeAndReply(suggested)
            return
        } else if (!pendingSuggestionFresh) {
            pendingSuggestedCommand = null
            pendingSuggestedAt = 0L
        }
        transcription?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
        transcription = null
        finishCommandListening()
        clearOwnerVoiceRetry()
        val normalizedCommand = norm(command)
        if (normalizedCommand in setOf("cancelar", "cancele", "pare", "esquece")) { cancelRequest(); return }
        val fastCommand = isFastDeviceCommand(normalizedCommand)
        if (fromVoice) {
            val confidence = averageRecognitionConfidence(json)
            val audioNoise = lastAudioMetrics
            val crowdedOrWindy = audioNoise.severeNoise || audioNoise.noiseRms >= 900.0
            val sensitiveNoiseAction =
                WhatsAppCommandParser.looksLikeSendCommand(normalizedCommand) ||
                AutomationCommandRouter.looksLikeAutomationCommand(normalizedCommand)

            // Speaker focus is a crowd disambiguator, not a reason to reject normal
            // questions/open-app commands. Only a sensitive action is held when the
            // enrolled speaker evidence strongly disagrees.
            if (crowdedOrWindy && OwnerIdentity.voiceOptIn(this) &&
                speakerFrames(json) >= OWNER_MIN_SPK_FRAMES) {
                val focusAssessment = assessOwnerVoice(json)
                val strongOtherSpeaker =
                    focusAssessment.decision == OwnerVoiceDecision.REJECT &&
                    focusAssessment.score >= 0.0 &&
                    focusAssessment.score < focusAssessment.threshold - 0.18

                if (strongOtherSpeaker) {
                    updateNotification("Ambiente cheio • outra voz ignorada")
                    return
                }
                if (sensitiveNoiseAction &&
                    focusAssessment.decision != OwnerVoiceDecision.MATCH) {
                    requestNoiseConfirmation(command)
                    return
                }
            }

            // In noise, do not force the user to repeat a whole sentence. Benign,
            // deterministic actions keep working. Sensitive actions get one
            // yes/no confirmation when ASR confidence is genuinely weak.
            if (GamaRecognitionPolicy.needsNoiseConfirmation(
                    crowdedOrWindy = crowdedOrWindy,
                    sensitiveAction = sensitiveNoiseAction,
                    confidence = confidence,
                )) {
                requestNoiseConfirmation(command)
                return
            }

            // Vosk word confidence is telemetry, not a hard gate for conversation.
            // Context and intent resolution get a chance before asking anything.
            if (confidence >= 0.0 && confidence < 0.45) {
                updateNotification("Fala reconhecida • usando contexto para interpretar")
            }
        }
        if (brainBusy || researchBusy) {
            if (fastCommand) {
                // A deterministic Android command wins over an older AI request.
                // The isolated brain may finish later, but its generation becomes stale
                // and it cannot overwrite the local reply.
                requestGeneration++
                responsePending = false
                responseWatchToken++
                if (brainBusy) {
                    brainHealthToken++
                    brainBusy = false
                    activeBrainRequestId = 0L
                    activeBrainPid = 0
                    try { stopService(Intent(this, GamaBrainProcessService::class.java)) } catch (_: Exception) {}
                }
            } else {
                speak("Ainda estou terminando a resposta anterior. Diga Gama, cancelar, se quiser interromper.", true)
                return
            }
        }
        // O aparelho é a autoridade para informação pessoal, mesmo com voz cadastrada.
        // Textos no app também passam por confirmação para evitar acesso casual.
        if (IdentityIntent.requiresDeviceProof(command) && !hasTrustedOwnerSession()) {
            requestOwnerAndroidProof(command)
            return
        }
        // O dono deste celular é o perfil local. A voz nunca autentica pedidos privados.
        pendingCommand.clear()
        requestGeneration++
        if (!fastCommand) armResponseDeadline() else markResponseStarted()
        executeAndReply(command)
    }

    private fun requestOwnerAndroidProof(command: String) {
        // Limpa o histórico privado, mas lembra se a conversa por voz estava aberta para retomá-la após o Android confirmar.
        ownerAuthResumeConversation = conversation.active
        conversation.close()
        GamaSupervisor.onConversationClosed()
        ownerAuthCommand = command
        ownerAuthUntil = SystemClock.elapsedRealtime() + 90000L
        val proof = Intent(this, MainActivity::class.java)
            .setAction("com.gama.assistant.AUTH_OWNER")
        launchSafely(proof)
        updateNotification("Confirme sua identidade no Android para continuar.")
        speak("Preciso confirmar sua identidade no Android para continuar este pedido.", true)
    }

    /** Called only by the app's Activity after a successful Android credential result. */
    fun completeOwnerAndroidProof() { postAudio {
        val command = ownerAuthCommand
        ownerAuthCommand = null
        val valid = SystemClock.elapsedRealtime() <= ownerAuthUntil
        ownerAuthUntil = 0L
        val resumeConversation = ownerAuthResumeConversation
        ownerAuthResumeConversation = false
        if (valid && !isDeviceLocked() && !command.isNullOrBlank()) {
            recentAndroidAuth = SystemClock.elapsedRealtime()
            if (resumeConversation) {
                conversation.open()
                GamaSupervisor.onWakeAccepted()
                touchConversation()
            }
            pendingCommand.clear()
            if (!isFastDeviceCommand(norm(command))) armResponseDeadline() else markResponseStarted()
            executeAndReply(command)
        }
    } }

    private fun clearOwnerVoiceRetry() {
        ownerRetryPending = false
        ownerRetryFirstScore = -1.0
        ownerRetryUntil = 0L
        challenge = ""
        challengeCommand = ""
    }

    private fun requestOwnerVoiceRetry(assessment: OwnerVoiceAssessment, command: String) {
        if (SystemClock.elapsedRealtime() < challengeCooldownUntil) {
            speak("Confirme sua identidade pelo Android antes de tentar novamente.", true)
            return
        }
        val random = SecureRandom()
        val colors = listOf("azul", "verde", "branco", "preto", "vermelho", "amarelo")
        val nouns = listOf("casa", "porta", "livro", "mesa", "janela", "barco", "rua", "sol")
        val numbers = listOf("um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove")
        challenge = "${colors[random.nextInt(colors.size)]} ${numbers[random.nextInt(numbers.size)]} ${nouns[random.nextInt(nouns.size)]} ${numbers[random.nextInt(numbers.size)]}"
        challengeCommand = command
        ownerRetryPending = true
        ownerRetryFirstScore = assessment.score
        ownerRetryThreshold = assessment.threshold
        finishCommandListening()
        armCommandWindowAfterTts = true
        speak("Confirme dizendo: $challenge.", false)
    }

    private fun verifyChallenge(spoken: String, json: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val assessment = assessOwnerVoice(json)
        val androidRecent = !isDeviceLocked() && recentAndroidAuth > 0L && now - recentAndroidAuth < 90000L
        val sessionRecent = recentOwnerSession > 0L && now - recentOwnerSession < 60000L
        val allowance = if (androidRecent) 0.06 else if (sessionRecent) 0.04 else 0.02
        val required = maxOf(0.60, ownerRetryThreshold - allowance)
        val phraseOk = VoicePolicy.challengeMatches(spoken, challenge)
        val accepted = phraseOk && now <= ownerRetryUntil && speakerFrames(json) >= 75 &&
            assessment.score >= required &&
            (ownerRetryFirstScore < 0.0 || ownerRetryFirstScore >= ownerRetryThreshold - 0.12)
        val command = challengeCommand
        clearOwnerVoiceRetry() // single use, even for a wrong or expired response.
        finishCommandListening()
        if (accepted) {
            challengeFailures = 0
            recentOwnerSession = now
            maybeAdaptOwner(json, assessment)
            if (!isFastDeviceCommand(norm(command))) armResponseDeadline() else markResponseStarted()
            executeAndReply(command)
        } else {
            challengeFailures++
            if (challengeFailures >= 3) { challengeCooldownUntil = now + 60000L; challengeFailures = 0 }
            requestOwnerAndroidProof(command)
        }
    }

    private fun maybeAdaptOwner(json: JSONObject, assessment: OwnerVoiceAssessment) {
        val now = SystemClock.elapsedRealtime()
        if (isDeviceLocked() || recentAndroidAuth == 0L || now - recentAndroidAuth > 90000L ||
            now - lastAdaptedAt < 300000L || speakerFrames(json) < 150 ||
            assessment.score < maxOf(0.84, assessment.threshold + 0.12)) return
        val sample = extractSpeakerVector(json)?.let(::normalizeSpeakerVector) ?: return
        val refs = loadOwnerSamples()
        if (refs.size < 7 || refs.any { cosineSimilarity(it, sample) > 0.97 }) return
        val updated = refs.take(7) + refs.drop(7).takeLast(1) + listOf(sample)
        val center = loadOwnerVector() ?: return
        saveOwnerProfile(center, updated, speakerFrames(json), adaptiveOwnerThreshold(refs.take(7)))
        lastAdaptedAt = now
    }

    private fun startEnrollmentWhenReady() {
        if (!enrollmentRequested || recognizer == null || (!ttsReady && !ttsFailed)) return
        enrollmentRequested = false
        if (isDeviceLocked()) { speak("Desbloqueie o Android para cadastrar sua voz.", true); return }
        clearOwnerVoiceRetry()
        queuedWakeCommand = null
        recentOwnerSession = 0L
        ownerEnrollmentVectors.clear()
        cachedReferences = null
        ownerEnrollmentMode = true
        enrollmentStartedAt = SystemClock.elapsedRealtime()
        recognizer?.reset()
        finishCommandListening()
        speak("Vamos cadastrar sete frases. Use sua voz natural, variando suavemente o volume e a distância. Diga: ${ownerEnrollmentPhrase(0)}", false)
    }

    private fun ownerEnrollmentPhrase(index: Int): String {
        val phrases = listOf(
            "Este aparelho é meu e esta é a minha voz.",
            "Hoje eu quero abrir a câmera do meu celular.",
            "Por favor me diga que horas são agora.",
            "Aumente um pouco o volume da minha música.",
            "Estou falando baixo perto do meu telefone.",
            "Quero fazer uma pesquisa sobre uma ideia nova.",
            "Posso falar devagar e também um pouco mais rápido."
        )
        return phrases[index.coerceIn(0, phrases.lastIndex)]
    }

    private fun handleOwnerEnrollmentSample(spoken: String, json: JSONObject) {
        val rawSample = extractSpeakerVector(json)
        val frames = speakerFrames(json)
        val sample = rawSample?.let(::normalizeSpeakerVector)

        if (sample == null || frames < OWNER_ENROLL_MIN_SPK_FRAMES) {
            updateNotification("Amostra curta. Fale a frase completa e com calma.")
            val phrase = ownerEnrollmentPhrase(ownerEnrollmentVectors.size)
            speak(
                "A amostra ficou curta. Repita com calma: $phrase",
                false
            )
            return
        }

        // Enrollment is authorized by Android; natural variation is intentional.
        if (isDeviceLocked()) {
            ownerEnrollmentMode = false
            ownerEnrollmentVectors.clear()
            speak("Desbloqueie o Android para continuar o cadastro.", true)
            return
        }
        if (!phraseCloseEnough(spoken, ownerEnrollmentPhrase(ownerEnrollmentVectors.size))) {
            speak("Diga a frase completa: ${ownerEnrollmentPhrase(ownerEnrollmentVectors.size)}", false)
            return
        }

        if (ownerEnrollmentVectors.isNotEmpty() && sample.size != ownerEnrollmentVectors.first().size) return
        ownerEnrollmentVectors += sample
        val count = ownerEnrollmentVectors.size

        if (count < OWNER_ENROLL_SAMPLES) {
            val nextPhrase = ownerEnrollmentPhrase(count)
            updateNotification("Cadastro da voz do dono: amostra ${count + 1} de $OWNER_ENROLL_SAMPLES.")
            speak(
                "Amostra $count de $OWNER_ENROLL_SAMPLES salva. Agora diga: $nextPhrase",
                false
            )
            return
        }

        val size = ownerEnrollmentVectors.minOf { it.size }
        val average = FloatArray(size)
        for (vector in ownerEnrollmentVectors) {
            for (i in 0 until size) {
                average[i] += vector[i]
            }
        }
        for (i in average.indices) {
            average[i] /= ownerEnrollmentVectors.size.toFloat()
        }

        val centroid = normalizeSpeakerVector(average)
        if (centroid == null) {
            ownerEnrollmentVectors.clear()
            ownerEnrollmentMode = false
            updateNotification("Não foi possível criar o perfil de voz.")
            speak("Não consegui criar seu perfil de voz. Abra o Gama e tente cadastrar novamente.", true)
            return
        }

        val threshold = adaptiveOwnerThreshold(ownerEnrollmentVectors)
        saveOwnerProfile(
            centroid = centroid,
            samples = ownerEnrollmentVectors.toList(),
            frames = frames,
            threshold = threshold
        )
        OwnerIdentity.setVoiceOptIn(this, true)
        ownerEnrollmentVectors.clear()
        ownerEnrollmentMode = false
        updateNotification("Voz do dono cadastrada.")
        speak("Voz cadastrada. Diga Gama quando precisar.", true)
    }

    private fun ensureSpeakerModel(): File {
        val dest = File(filesDir, "vosk-speaker-model")
        val marker = File(dest, ".ready")
        if (marker.exists()) return dest
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        copyAssetTree("spk-model", dest)
        marker.writeText("ok")
        return dest
    }

    private fun ensureModel(): File {
        val dest = File(filesDir, "vosk-model-pt")
        val marker = File(dest, ".ready")
        if (marker.exists()) return dest
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        copyAssetTree("model", dest)
        marker.writeText("ok")
        return dest
    }

    private fun copyAssetTree(assetPath: String, out: File) {
        val list = assets.list(assetPath) ?: emptyArray()
        if (list.isEmpty()) {
            out.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        } else {
            out.mkdirs()
            for (name in list) copyAssetTree("$assetPath/$name", File(out, name))
        }
    }

    private fun acquireListenerWakeLock() {
        if (!BackgroundMode.keepCpuAwake(this)) return
        try {
            if (listenerWakeLock?.isHeld == true) return

            val power =
                getSystemService(Context.POWER_SERVICE)
                    as PowerManager

            listenerWakeLock =
                power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:GamaVoiceListener"
                ).apply {
                    setReferenceCounted(false)
                    // Mantém CPU/microfone ativos com a tela apagada.
                    // O serviço libera este lock no onDestroy.
                    acquire()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível manter wake lock do ouvinte", e)
        }
    }

    private fun sendLockConversationUi(action: String) {
    try {
        sendBroadcast(Intent(action).setPackage(packageName))
    } catch (_: Exception) {}
}

private fun showLockConversationUi() {
    if (!isDeviceLocked()) return

    val intent =
        Intent(this, GamaLockConversationActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

    uiHandler.post {
        if (!running || !isDeviceLocked()) return@post

        try {
            when (
                BackgroundLaunchPolicy.route(
                    GamaApplication.foreground,
                    Settings.canDrawOverlays(this),
                    SystemAssistant.current != null
                )
            ) {
                BackgroundLaunchPolicy.Route.ACTIVITY ->
                    super.startActivity(intent)

                BackgroundLaunchPolicy.Route.ASSISTANT ->
                    SystemAssistant.current?.present(intent)

                BackgroundLaunchPolicy.Route.NOTIFICATION -> {
                    pendingActivity = intent
                    updateNotification(
                        "Gama está ouvindo • toque em Continuar pedido se o visual não abrir"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Não foi possível abrir o visual da tela bloqueada",
                e
            )
            pendingActivity = intent
            updateNotification(
                "Gama está ouvindo • toque em Continuar pedido se o visual não abrir"
            )
        }

        uiHandler.postDelayed({
            if (
                running &&
                isDeviceLocked() &&
                conversation.active
            ) {
                sendLockConversationUi(
                    GamaLockConversationActivity.ACTION_LISTENING
                )
            }
        }, 220L)
    }
}

    private fun setConversationVisual(mode: GamaEnergyView.Mode) {
        when (mode) {
            GamaEnergyView.Mode.LISTENING -> GamaSupervisor.onListening()
            GamaEnergyView.Mode.THINKING -> GamaSupervisor.onThinking()
            GamaEnergyView.Mode.SPEAKING -> GamaSupervisor.onSpeaking()
        }
        uiHandler.post {
            if (!running) return@post
            try {
                orb?.mode = mode
                if (isDeviceLocked()) {
                    val action = when (mode) {
                        GamaEnergyView.Mode.LISTENING -> GamaLockConversationActivity.ACTION_LISTENING
                        GamaEnergyView.Mode.THINKING -> GamaLockConversationActivity.ACTION_THINKING
                        GamaEnergyView.Mode.SPEAKING -> GamaLockConversationActivity.ACTION_SPEAKING
                    }
                    sendLockConversationUi(action)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha visual isolada; voz continua ativa", e)
                try { orb?.release() } catch (_: Exception) {}
                try { orb?.let { wm?.removeView(it) } } catch (_: Exception) {}
                orb = null
            }
        }
    }

    private fun showWakeUi() {
        if (isDeviceLocked()) {
    showLockConversationUi()
    return
}
        AssistantRuntime.state("Ouvindo seu pedido")
        uiHandler.post {
            val power = getSystemService(PowerManager::class.java)
            if (running && power.isInteractive && !isDeviceLocked()) showOrb()
        }
    }

    private fun hideWakeUi() {
        sendLockConversationUi(
    GamaLockConversationActivity.ACTION_CLOSE
)
        uiHandler.post {
            removeOrb()
            try {
                sendBroadcast(Intent(ACTION_CLOSE_UI).setPackage(packageName))
            } catch (_: Exception) {}
        }
    }

    private fun scheduleUiTimeout(delayMs: Long) {
        uiHandler.removeCallbacks(uiTimeout)
        uiHandler.postDelayed(uiTimeout, delayMs)
    }

    private fun showOrb() {
    if (!Settings.canDrawOverlays(this)) return
    if (orb != null) return
    wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    orb = GamaEnergyView(this).apply {
        mode = GamaEnergyView.Mode.LISTENING
        alpha = 1f
        elevation = dp(16).toFloat()
        contentDescription = "Gama"
    }
    val type = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
    orbParams = WindowManager.LayoutParams(
        dp(96), dp(96), type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = dp(10)
        y = dp(38)
    }
    makeOrbDraggable()
    try {
        wm?.addView(orb, orbParams)
        setConversationVisual(GamaEnergyView.Mode.LISTENING)
    } catch (e: Exception) {
        Log.w(TAG, "Não foi possível mostrar a bolinha", e)
        orb?.release()
        orb = null
        updateNotification("Escuta ativa • autorize a bolinha nas configurações do Gama")
    }
}

private fun makeOrbDraggable() {
        val view = orb ?: return
        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: android.view.View?, event: MotionEvent): Boolean {
                val params = orbParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX - (event.rawX - touchX)).toInt()
                        params.y = (startY - (event.rawY - touchY)).toInt()
                        try { wm?.updateViewLayout(view, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = kotlin.math.abs(event.rawX - touchX)
                        val dy = kotlin.math.abs(event.rawY - touchY)
                        if (dx < dp(14) && dy < dp(14)) {
                            if (speaking || ttsSpeaking) interruptSpeechForUser()
                            else if (conversation.active) postAudio { beginCommandWindow() }
                            else postAudio { acknowledgeWake() }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startIdleAnimation() {
    setConversationVisual(GamaEnergyView.Mode.LISTENING)
}

private fun setOrbListening() {
    setConversationVisual(GamaEnergyView.Mode.LISTENING)
}

private fun animateOrbWake() {
    setConversationVisual(GamaEnergyView.Mode.LISTENING)
}

private fun animateOrbSpeaking() {
    setConversationVisual(GamaEnergyView.Mode.SPEAKING)
}

private fun removeOrb() {
    val view = orb ?: return
    view.release()
    try { wm?.removeView(view) } catch (_: Exception) {}
    orb = null
}

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Gama em segundo plano", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun isDeviceAdminActiveForUi(): Boolean = try {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(this, GamaDeviceAdminReceiver::class.java))
    } catch (_: Exception) {
        false
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val pause = PendingIntent.getService(this, 45, Intent(this, GamaService::class.java).setAction("com.gama.assistant.STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.addAction(android.R.drawable.ic_menu_preferences, "Ajustes", PendingIntent.getActivity(this, 46,
            Intent(this, MainActivity::class.java).setAction("com.gama.assistant.SETTINGS"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (!isDeviceAdminActiveForUi()) {
            val adminSetup = PendingIntent.getActivity(
                this,
                47,
                Intent(this, MainActivity::class.java).setAction("com.gama.assistant.ACTIVATE_DEVICE_ADMIN"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_lock_lock, "Ativar bloqueio", adminSetup)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pausar voz", pause)
        if (!unlockPending) pendingActivity?.let {
            builder.addAction(android.R.drawable.ic_menu_view, "Continuar pedido", PendingIntent.getActivity(this, 44, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (unlockPending) {
            val unlock = PendingIntent.getActivity(this, 43,
                Intent(this, LockScreenActivity::class.java).putExtra(EXTRA_REQUEST_SECURE_UNLOCK, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_lock_lock, "Desbloquear", unlock)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentTitle("Gama")
            .setContentText(if (isDeviceLocked()) "Ativo • diga Gama" else text.take(180))
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val safe = text.filter { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }.take(700)
        try { AssistantRuntime.state(safe) } catch (_: Exception) {}
        if (textMode || !running) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification(safe))
        } catch (e: Exception) {
            Log.e(TAG, "Falha isolada ao atualizar notificação do Gama", e)
        }
    }

    // GAMA67_STABILITY_MEMBERS
    private var brainProcessSupervisor: BrainProcessSupervisor? = null
    private var listenerRestartDelayMs = 750L
    private var listenerRestartPending = false

    private val listenerSupervisorRunnable = object : Runnable {
        override fun run() {
            if (BackgroundMode.enabled(this@GamaService)) {
                val now = SystemClock.elapsedRealtime()
                val alive = listenerThread?.isAlive == true
                val staleAudio = lastAudioReadAt > 0L && now - lastAudioReadAt > 5_000L
                when {
                    // A previous recovery can leave the service alive in text mode.
                    // Do not let that become a permanent "deaf" state.
                    textMode || !running -> {
                        if (!listenerRestartPending) enableVoice()
                    }
                    alive && voiceReady && !staleAudio -> {
                        listenerRestartDelayMs = 750L
                    }
                    else -> {
                        recoverMicrophoneOnly(if (staleAudio) "watchdog-stale-audio" else "watchdog")
                    }
                }
            }
            uiHandler.postDelayed(this, 3_000L)
        }
    }

    private fun pauseVoiceWithoutStoppingService() {
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { wakeRecognizer?.reset() }
        runCatching { sleepRecognizer?.reset() }
        wakePreRoll = ByteArray(0)
        lastDedicatedSleepPartialAt = 0L
        listenerThread?.interrupt()
        listenerThread = null
        listenerRestartPending = false
        updateNotification("Gama ativo • voz pausada")
    }

    private fun handleRecoverableWorkerFailure(threadName: String, errorClass: String) {
        CrashRecorder.recordSoft(
            this,
            if (threadName == "gama-listener") "microphone" else "worker",
            errorClass
        )

        val delay = GamaRecoveryCoordinator.nextDelayMs(threadName)

        when (threadName) {
            "gama-listener" -> {
                uiHandler.postDelayed({
                    if (BackgroundMode.enabled(this) && listenerThread?.isAlive != true) {
                        recoverMicrophoneOnly("worker")
                    }
                }, delay)
            }

            "gama-periodic-check",
            "gama-calendar-awareness",
            "gama-ambient-awareness" -> {
                uiHandler.postDelayed({
                    if (running) scheduleAutomationTick()
                }, delay)
            }

            "gama-live-info",
            "gama-research",
            "gama-local-vision",
            "gama-super-tool",
            "gama-intro-capabilities" -> {
                uiHandler.postDelayed({
                    if (running) {
                        updateNotification("Gama recuperado • pronto")
                        setConversationVisual(GamaEnergyView.Mode.LISTENING)
                    }
                }, delay)
            }
        }
    }

    private fun recoverMicrophoneOnly(source: String) {
        if (!BackgroundMode.enabled(this) || listenerRestartPending) return
        listenerRestartPending = true
        running = false
        voiceReady = false
        lastAudioReadAt = 0L
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { wakeRecognizer?.reset() }
        runCatching { sleepRecognizer?.reset() }
        wakePreRoll = ByteArray(0)
        lastDedicatedSleepPartialAt = 0L
        listenerThread?.interrupt()
        listenerThread = null
        // Put the service back in the state from which enableVoice can create
        // a fresh AudioRecord/Recognizer pair.
        textMode = true

        val delay = listenerRestartDelayMs
        listenerRestartDelayMs = (listenerRestartDelayMs * 2L).coerceAtMost(8_000L)
        CrashRecorder.recordSoft(this, "microphone", "restart-$source")
        updateNotification("Recuperando escuta do Gama...")
        uiHandler.postDelayed({
            listenerRestartPending = false
            if (BackgroundMode.enabled(this) && listenerThread?.isAlive != true) {
                enableVoice()
            }
        }, delay)
    }

    /** Clears a crashed isolated-brain request without speaking internal recovery details. */
    private fun resetIsolatedBrainForSilentRetry(requestId: Long) {
        if (requestId != activeBrainRequestId) return
        val pid = activeBrainPid
        brainHealthToken++
        brainBusy = false
        activeBrainRequestId = 0L
        activeBrainPid = 0
        brainCooldownUntil = 0L
        try { stopService(Intent(this, GamaBrainProcessService::class.java)) } catch (_: Exception) {}
        if (pid > 0 && pid != android.os.Process.myPid()) {
            try { GamaProcessSafety.detachFailedBrain(pid) } catch (_: Exception) {}
        }
        updateNotification("Gama ativo • recuperando raciocínio local")
    }

    private fun handleBrainProcessDeath() {
        // GAMA76_BRAIN_IPC_RECOVERY
        val requestId = activeBrainRequestId
        val retryPrompt = brainRetryPrompt
        val retrySequence = brainRetrySequence

        if (requestId != 0L && retryPrompt.isNotBlank() && brainRetryAttempt < 2) {
            brainRetryAttempt += 1
            android.util.Log.w("GamaBrain", "Isolated brain process died; silent recovery attempt $brainRetryAttempt")
            resetIsolatedBrainForSilentRetry(requestId)
            val delayMs = if (brainRetryAttempt == 1) 1_200L else 2_800L
            uiHandler.postDelayed({
                if (userCommandSequence == retrySequence &&
                    brainRetryPrompt == retryPrompt &&
                    activeBrainRequestId == 0L
                ) {
                    answerWithBrainAsync(retryPrompt)
                }
            }, delayMs)
        } else if (requestId != 0L) {
            brainRetryAttempt = 0
            CrashRecorder.recordSoft(this, "brain IPC", "isolated-process-unavailable")
            failIsolatedBrain(requestId, "Não consegui concluir essa resposta agora, mas continuo ativo.")
        } else {
            brainBusy = false
            activeBrainRequestId = 0L
        }
    }

    // GAMA76_STATE
    private var userCommandSequence = 0L
    private var brainRetryPrompt = ""
    private var brainRetryAttempt = 0
    private var brainRetrySequence = 0L
}
