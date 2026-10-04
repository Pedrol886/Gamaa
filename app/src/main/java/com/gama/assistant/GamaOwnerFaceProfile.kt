package com.gama.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

/**
 * Local owner-face profile used by Gama for personalization and owner recognition.
 *
 * It intentionally stores no photo. A normalized geometric face signature is
 * encrypted with a key held by Android Keystore. This is not a replacement for
 * Android biometric security; sensitive actions still require the official
 * BiometricPrompt / device credential path.
 */
object GamaOwnerFaceProfile {
    private const val PREFS = "gama_owner_face_v1"
    private const val KEY_BLOB = "face_signature"
    private const val KEY_SAMPLES = "face_samples"
    private const val KEY_ALIAS = "gama_owner_face_keystore_v1"
    private const val MIN_SAMPLES = 10
    private const val MATCH_DISTANCE = 0.062f

    private val landmarkTypes = intArrayOf(
        FaceLandmark.LEFT_EYE,
        FaceLandmark.RIGHT_EYE,
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
        FaceLandmark.MOUTH_BOTTOM,
        FaceLandmark.LEFT_CHEEK,
        FaceLandmark.RIGHT_CHEEK,
    )

    fun hasProfile(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(KEY_BLOB)

    fun sampleCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SAMPLES, 0)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun vector(face: Face): FloatArray? {
        val box = face.boundingBox
        val width = box.width().toFloat().takeIf { it > 1f } ?: return null
        val height = box.height().toFloat().takeIf { it > 1f } ?: return null
        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY()

        val out = ArrayList<Float>(landmarkTypes.size * 2 + 1)
        for (type in landmarkTypes) {
            val p = face.getLandmark(type)?.position ?: return null
            out += (p.x - centerX) / width
            out += (p.y - centerY) / height
        }
        out += width / height
        return out.toFloatArray()
    }

    fun saveSamples(context: Context, samples: List<FloatArray>): Boolean {
        if (samples.size < MIN_SAMPLES) return false
        val size = samples.firstOrNull()?.size ?: return false
        if (size == 0 || samples.any { it.size != size }) return false

        val mean = FloatArray(size)
        samples.forEach { sample ->
            for (i in sample.indices) mean[i] += sample[i]
        }
        for (i in mean.indices) mean[i] /= samples.size.toFloat()

        // Reject wildly inconsistent enrollment sets instead of saving noise.
        val averageDistance = samples.map { distance(it, mean) }.average().toFloat()
        if (!averageDistance.isFinite() || averageDistance > 0.075f) return false

        val buffer = ByteBuffer.allocate(4 + mean.size * 4)
        buffer.putInt(mean.size)
        for (value in mean) buffer.putFloat(value)
        val bytes = buffer.array()
        val encrypted = encrypt(bytes) ?: return false

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putInt(KEY_SAMPLES, samples.size)
            .apply()
        return true
    }

    fun matches(context: Context, candidate: FloatArray): Boolean {
        val enrolled = load(context) ?: return false
        if (enrolled.size != candidate.size) return false
        return distance(enrolled, candidate) <= MATCH_DISTANCE
    }

    fun similarity(context: Context, candidate: FloatArray): Float? {
        val enrolled = load(context) ?: return null
        if (enrolled.size != candidate.size) return null
        val d = distance(enrolled, candidate)
        return (1f - (d / 0.14f)).coerceIn(0f, 1f)
    }

    private fun load(context: Context): FloatArray? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOB, null) ?: return null
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val bytes = decrypt(encrypted) ?: return null
        if (bytes.size < 8) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(bytes)
            val size = buffer.int
            if (size !in 1..128 || buffer.remaining() != size * 4) return null
            FloatArray(size) { buffer.getFloat() }
        }.getOrNull()
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return Float.POSITIVE_INFINITY
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum / a.size.toFloat())
    }

    private fun secretKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
    }.getOrNull()

    private fun encrypt(plain: ByteArray): ByteArray? = runCatching {
        val key = secretKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        ByteBuffer.allocate(1 + iv.size + body.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(body)
            .array()
    }.getOrNull()

    private fun decrypt(blob: ByteArray): ByteArray? = runCatching {
        if (blob.size < 14) return null
        val buffer = ByteBuffer.wrap(blob)
        val ivSize = buffer.get().toInt() and 0xff
        if (ivSize !in 12..16 || buffer.remaining() <= ivSize) return null
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val body = ByteArray(buffer.remaining())
        buffer.get(body)
        val key = secretKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.doFinal(body)
    }.getOrNull()
}
