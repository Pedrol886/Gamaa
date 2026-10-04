package com.gama.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Gama face gate UI.
 *
 * Camera/ML Kit is used for live face presence and the requested visual
 * experience only. Identity authority remains Android's official biometric
 * system. Gama never stores a facial image or biometric template.
 */
class GamaFaceGateActivity : FragmentActivity() {
    companion object {
        const val EXTRA_UNLOCK_MODE = "gama_face_unlock_mode"
        const val EXTRA_ENROLL_OWNER_FACE = "gama_enroll_owner_face"
        private const val CAMERA_PERMISSION_REQUEST = 8302
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var detector: FaceDetector
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val authStarted = AtomicBoolean(false)
    private var stableFaceFrames = 0
    private var cameraProvider: ProcessCameraProvider? = null
    private val enrollmentSamples = mutableListOf<FloatArray>()
    private var lastEnrollmentSampleAt = 0L
    private var localOwnerMatchFrames = 0
    private val unlockMode: Boolean
        get() = intent?.getBooleanExtra(EXTRA_UNLOCK_MODE, false) == true
    private val enrollOwnerFaceMode: Boolean
        get() = intent?.getBooleanExtra(EXTRA_ENROLL_OWNER_FACE, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.rgb(5, 5, 7)
        window.navigationBarColor = Color.rgb(5, 5, 7)

        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                )
                .setLandmarkMode(
                    FaceDetectorOptions.LANDMARK_MODE_ALL
                )
                .setClassificationMode(
                    FaceDetectorOptions.CLASSIFICATION_MODE_NONE
                )
                .enableTracking()
                .build()
        )

        buildUi()

        if (
            checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            statusView.text =
                "Permita a câmera para o Gama detectar a presença do seu rosto."
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST,
            )
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 5, 7))
        }

        val title = TextView(this).apply {
            text = when {
                enrollOwnerFaceMode -> "Gama • cadastrar rosto do dono"
                unlockMode -> "Gama • desbloqueio seguro"
                else -> "Gama • verificação facial"
            }
            setTextColor(Color.WHITE)
            textSize = 21f
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(70),
                Gravity.TOP,
            ).apply {
                topMargin = dp(34)
            },
        )

        val scanner = FrameLayout(this)
        val scannerSize = dp(340)
        root.addView(
            scanner,
            FrameLayout.LayoutParams(
                scannerSize,
                scannerSize,
                Gravity.CENTER,
            )
        )

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val side = minOf(view.width, view.height)
                    val left = (view.width - side) / 2
                    val top = (view.height - side) / 2
                    outline.setOval(
                        left,
                        top,
                        left + side,
                        top + side,
                    )
                }
            }
        }
        val faceSize = dp(244)
        scanner.addView(
            previewView,
            FrameLayout.LayoutParams(
                faceSize,
                faceSize,
                Gravity.CENTER,
            )
        )

        scanner.addView(
            GamaFaceRingView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        statusView = TextView(this).apply {
            text = if (enrollOwnerFaceMode) {
                "Posicione seu rosto no centro. Vou registrar várias amostras localmente."
            } else {
                "Posicione seu rosto no centro da logo."
            }
            setTextColor(Color.rgb(222, 222, 228))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(100),
                Gravity.BOTTOM,
            ).apply {
                bottomMargin = dp(48)
            },
        )

        setContentView(root)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults,
        )
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        if (
            grantResults.firstOrNull() ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            GamaFaceSessionCoordinator.reportAndClear(
                "Sem a permissão da câmera eu não consigo abrir a leitura facial."
            )
            finish()
        }
    }

    private fun startCamera() {
        statusView.text = "Lendo seu rosto..."
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                val provider = runCatching { future.get() }.getOrNull()
                if (provider == null) {
                    GamaFaceSessionCoordinator.reportAndClear(
                        "Não consegui iniciar a câmera frontal agora."
                    )
                    finish()
                    return@addListener
                }
                cameraProvider = provider
                bindCamera(provider)
            },
            Executor { runnable -> runOnUiThread(runnable) },
        )
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(
                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            )
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            analyzeFace(imageProxy)
        }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
            )
        }.onFailure {
            GamaFaceSessionCoordinator.reportAndClear(
                "Não consegui usar a câmera frontal agora."
            )
            finish()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFace(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || authStarted.get()) {
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.firstOrNull()
                val good = face?.let(::isCenteredFace) == true
                stableFaceFrames = if (good) stableFaceFrames + 1 else 0

                if (good && face != null) {
                    if (enrollOwnerFaceMode) {
                        collectEnrollmentSample(face)
                    } else {
                        val profileExists = GamaOwnerFaceProfile.hasProfile(this)
                        val candidate = GamaOwnerFaceProfile.vector(face)
                        val ownerMatches = !profileExists ||
                            (candidate != null && GamaOwnerFaceProfile.matches(this, candidate))
                        localOwnerMatchFrames = if (ownerMatches) localOwnerMatchFrames + 1 else 0
                        runOnUiThread {
                            statusView.text = when {
                                !profileExists && stableFaceFrames >= 2 ->
                                    "Rosto detectado. Confirme sua identidade pelo Android."
                                profileExists && ownerMatches && localOwnerMatchFrames >= 2 ->
                                    "Rosto do dono reconhecido. Confirmando pelo Android..."
                                profileExists && !ownerMatches ->
                                    "Este rosto não corresponde ao dono cadastrado no Gama."
                                else -> "Mantenha o rosto no centro..."
                            }
                        }

                        if (
                            stableFaceFrames >= 4 &&
                            localOwnerMatchFrames >= 3 &&
                            authStarted.compareAndSet(false, true)
                        ) {
                            runOnUiThread {
                                cameraProvider?.unbindAll()
                                startAndroidAuthentication()
                            }
                        }
                    }
                } else {
                    localOwnerMatchFrames = 0
                }
            }
            .addOnFailureListener {
                stableFaceFrames = 0
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun collectEnrollmentSample(face: Face) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnrollmentSampleAt < 170L) return
        val vector = GamaOwnerFaceProfile.vector(face) ?: run {
            runOnUiThread {
                statusView.text = "Aproxime um pouco e olhe de frente para eu capturar os pontos do rosto."
            }
            return
        }
        lastEnrollmentSampleAt = now
        enrollmentSamples += vector
        val target = 12
        runOnUiThread {
            statusView.text = "Registrando rosto do dono: ${enrollmentSamples.size}/$target"
        }
        if (enrollmentSamples.size >= target && authStarted.compareAndSet(false, true)) {
            val saved = GamaOwnerFaceProfile.saveSamples(this, enrollmentSamples)
            runOnUiThread {
                cameraProvider?.unbindAll()
                if (saved) {
                    statusView.text = "Rosto do dono cadastrado localmente no Gama."
                    GamaFaceSessionCoordinator.reportAndClear(
                        "Pronto. Gravei seu perfil facial local como rosto do dono."
                    )
                } else {
                    statusView.text = "As amostras ficaram inconsistentes. Tente cadastrar novamente com boa luz."
                    GamaFaceSessionCoordinator.reportAndClear(
                        "Não consegui criar um perfil facial consistente. Tente novamente com boa iluminação."
                    )
                }
                previewView.postDelayed({ finish() }, 900L)
            }
        }
    }

    private fun isCenteredFace(face: Face): Boolean {
        val box = face.boundingBox
        if (box.width() < 120 || box.height() < 120) return false
        if (abs(face.headEulerAngleY) > 28f) return false
        if (abs(face.headEulerAngleZ) > 28f) return false
        return true
    }

    private fun startAndroidAuthentication() {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        val available =
            BiometricManager.from(this)
                .canAuthenticate(authenticators)

        if (available != BiometricManager.BIOMETRIC_SUCCESS) {
            authStarted.set(false)
            statusView.text =
                "Cadastre sua biometria facial nas configurações do Android primeiro."
            GamaFaceSessionCoordinator.reportAndClear(
                "Cadastre sua biometria facial nas configurações do Android primeiro."
            )
            finish()
            return
        }

        val prompt = BiometricPrompt(
            this,
            Executor { runnable -> runOnUiThread(runnable) },
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    super.onAuthenticationSucceeded(result)
                    GamaFaceAuthSession.markAuthenticated()
                    statusView.text = "Identidade confirmada."
                    if (unlockMode) {
                        requestOfficialUnlock()
                    } else {
                        GamaFaceSessionCoordinator.approvePrivateCommand()
                        finish()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    statusView.text =
                        "O Android não confirmou a identidade. Tente novamente."
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    super.onAuthenticationError(
                        errorCode,
                        errString,
                    )
                    GamaFaceSessionCoordinator.reportAndClear(
                        "A confirmação de identidade foi cancelada."
                    )
                    finish()
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Gama")
            .setSubtitle(
                "Confirme sua identidade pelo sistema seguro do Android"
            )
            .setNegativeButtonText("Cancelar")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(info)
    }

    private fun requestOfficialUnlock() {
        val keyguard =
            getSystemService(KeyguardManager::class.java)

        if (keyguard == null || !keyguard.isKeyguardLocked) {
            GamaFaceSessionCoordinator.reportAndClear(
                "Identidade confirmada. O celular já está desbloqueado."
            )
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        GamaFaceSessionCoordinator.reportAndClear(
                            "Identidade confirmada. O Android liberou o aparelho."
                        )
                        finish()
                    }

                    override fun onDismissCancelled() {
                        GamaFaceSessionCoordinator.reportAndClear(
                            "Identidade confirmada, mas o Android manteve a tela bloqueada."
                        )
                        finish()
                    }

                    override fun onDismissError() {
                        GamaFaceSessionCoordinator.reportAndClear(
                            "Identidade confirmada, mas o Android não permitiu remover o bloqueio."
                        )
                        finish()
                    }
                },
            )
        } else {
            GamaFaceSessionCoordinator.reportAndClear(
                "Identidade confirmada. Use o desbloqueio oficial mostrado pelo Android."
            )
            finish()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        runCatching { detector.close() }
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
