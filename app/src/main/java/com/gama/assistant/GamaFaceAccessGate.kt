package com.gama.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager

object GamaFaceAccessGate {
    fun handle(
        context: Context,
        raw: String,
        onApproved: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): Boolean {
        if (GamaFaceCommandPolicy.isEnrollmentCommand(raw)) {
            GamaFaceSessionCoordinator.beginEnrollment(onStatus)
            launchFaceGate(context, unlockMode = false, enrollOwnerFace = true)
            return true
        }

        if (GamaFaceCommandPolicy.isUnlockCommand(raw)) {
            if (!isLocked(context)) {
                onStatus("O celular já está desbloqueado.")
                return true
            }
            GamaFaceSessionCoordinator.beginUnlock(onStatus)
            launchFaceGate(context, unlockMode = true)
            return true
        }

        if (
            GamaFaceCommandPolicy.isSensitiveCommand(raw) &&
            isLocked(context) &&
            !GamaFaceAuthSession.isFresh()
        ) {
            GamaFaceSessionCoordinator.beginPrivateCommand(
                command = raw,
                onApproved = onApproved,
                onStatus = onStatus,
            )
            launchFaceGate(context, unlockMode = false)
            return true
        }

        return false
    }

    private fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguard?.isDeviceLocked == true
        } else {
            @Suppress("DEPRECATION")
            keyguard?.isKeyguardLocked == true
        }
    }

    private fun launchFaceGate(
        context: Context,
        unlockMode: Boolean,
        enrollOwnerFace: Boolean = false,
    ) {
        val intent = Intent(
            context,
            GamaFaceGateActivity::class.java,
        )
            .putExtra(
                GamaFaceGateActivity.EXTRA_UNLOCK_MODE,
                unlockMode,
            )
            .putExtra(
                GamaFaceGateActivity.EXTRA_ENROLL_OWNER_FACE,
                enrollOwnerFace,
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

        if (runCatching { context.startActivity(intent) }.isFailure) {
            GamaFaceSessionCoordinator.reportAndClear(
                "Não consegui abrir a leitura facial agora."
            )
        }
    }

    private fun openOfficialEnrollment(
        context: Context,
        onStatus: (String) -> Unit,
    ) {
        val enrollment = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            .putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val opened = runCatching {
            context.startActivity(enrollment)
        }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

        onStatus(
            if (opened) {
                "Abri as configurações oficiais do Android para cadastrar sua biometria facial."
            } else {
                "Não consegui abrir as configurações de biometria agora."
            }
        )
    }
}
