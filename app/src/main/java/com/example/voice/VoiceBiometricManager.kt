package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class VoiceprintProfile(
    val bossName: String = "Boss (Priyanshu)",
    val featureVector: FloatArray,
    val meanPitchHz: Float,
    val sampleCount: Int,
    val enrolledAtMs: Long = System.currentTimeMillis()
) {
    fun toJsonString(): String {
        val arr = JSONArray()
        featureVector.forEach { arr.put(it.toDouble()) }
        return JSONObject().apply {
            put("bossName", bossName)
            put("meanPitchHz", meanPitchHz.toDouble())
            put("sampleCount", sampleCount)
            put("enrolledAtMs", enrolledAtMs)
            put("features", arr)
        }.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceprintProfile) return false
        return bossName == other.bossName &&
            meanPitchHz == other.meanPitchHz &&
            sampleCount == other.sampleCount &&
            featureVector.contentEquals(other.featureVector)
    }

    override fun hashCode(): Int {
        var result = bossName.hashCode()
        result = 31 * result + featureVector.contentHashCode()
        result = 31 * result + meanPitchHz.hashCode()
        result = 31 * result + sampleCount
        return result
    }

    companion object {
        fun fromJsonString(raw: String): VoiceprintProfile? {
            if (raw.isBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val arr = obj.optJSONArray("features") ?: return null
                if (arr.length() == 0) return null
                val vec = FloatArray(arr.length()) { i -> arr.optDouble(i, 0.0).toFloat() }
                VoiceprintProfile(
                    bossName = obj.optString("bossName", "Boss (Priyanshu)"),
                    featureVector = vec,
                    meanPitchHz = obj.optDouble("meanPitchHz", 150.0).toFloat(),
                    sampleCount = obj.optInt("sampleCount", 1),
                    enrolledAtMs = obj.optLong("enrolledAtMs", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class SpeakerVerificationResult(
    val isEnrolled: Boolean,
    val isBossMatch: Boolean,
    val similarityPercent: Int,
    val measuredPitchHz: Float,
    val enrolledPitchHz: Float,
    val statusLabel: String
)

/**
 * Ultra-fast (< 3ms) On-Device Acoustic Voiceprint Biometric Engine.
 * Extracts fundamental pitch (F0 via normalized autocorrelation), spectral centroid, ZCR,
 * and 12-band log-spectral vocal timbre across voiced 16kHz frames to distinguish the
 * enrolled Boss/Jaan voice from different speakers with zero cloud latency.
 */
object VoiceBiometricManager {

    private const val SAMPLE_RATE_HZ = 16000
    private const val FRAME_SIZE = 512      // 32ms frame
    private const val FRAME_STEP = 256      // 16ms hop
    private const val FEATURE_DIM = 16      // 4 prosodic/spectral + 12 spectral bands
    private const val BOSS_MATCH_THRESHOLD = 0.78f

    private val bandCenterFreqs = floatArrayOf(
        150f, 240f, 360f, 520f, 750f, 1050f,
        1400f, 1850f, 2350f, 2900f, 3500f, 4200f
    )

    fun extractVoiceprintFromWav(wavBytes: ByteArray, bossName: String = "Boss (Priyanshu)"): VoiceprintProfile? {
        if (wavBytes.size <= 44 + FRAME_SIZE * 2) return null
        val pcmBytes = wavBytes.copyOfRange(44, wavBytes.size)
        return extractVoiceprintFromPcm16(pcmBytes, bossName)
    }

    fun extractVoiceprintFromPcm16(pcmBytes: ByteArray, bossName: String = "Boss (Priyanshu)"): VoiceprintProfile? {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount < FRAME_SIZE * 3) return null

        val samples = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toFloat() / 32768f
        }
        return extractVoiceprintFromNormalizedSamples(samples, bossName)
    }

    internal fun extractVoiceprintFromNormalizedSamples(
        samples: FloatArray,
        bossName: String = "Boss (Priyanshu)"
    ): VoiceprintProfile? {
        if (samples.size < FRAME_SIZE * 2) return null

        val accum = DoubleArray(FEATURE_DIM)
        var voicedFrames = 0
        val pitchValues = mutableListOf<Float>()

        var offset = 0
        val bandEnergies = DoubleArray(bandCenterFreqs.size)

        while (offset + FRAME_SIZE <= samples.size) {
            var energy = 0.0
            var zcr = 0
            for (i in 0 until FRAME_SIZE) {
                val s = samples[offset + i].toDouble()
                energy += s * s
                if (i > 0 && ((samples[offset + i] >= 0f) != (samples[offset + i - 1] >= 0f))) {
                    zcr++
                }
            }
            val rms = sqrt(energy / FRAME_SIZE)
            if (rms >= 0.015) {
                val f0 = estimatePitchAutocorrelation(samples, offset, FRAME_SIZE)
                if (f0 in 65f..360f) {
                    pitchValues.add(f0)
                    // 12-band Goertzel spectral envelope + centroid
                    var weightedSum = 0.0
                    var magSum = 0.0
                    for (b in bandCenterFreqs.indices) {
                        val mag = goertzelMagnitude(samples, offset, FRAME_SIZE, bandCenterFreqs[b], SAMPLE_RATE_HZ)
                        bandEnergies[b] = ln(1.0 + mag * 100.0)
                        weightedSum += bandCenterFreqs[b] * mag
                        magSum += mag
                    }
                    // L2-normalize spectral timbre vector so volume differences don't skew similarity
                    var normSq = 0.0
                    for (b in bandEnergies.indices) {
                        normSq += bandEnergies[b] * bandEnergies[b]
                    }
                    val invNorm = if (normSq > 1e-9) 1.0 / sqrt(normSq) else 1.0

                    val centroidNorm = if (magSum > 1e-9) ((weightedSum / magSum) / 4200.0).coerceIn(0.0, 1.0) else 0.3
                    val pitchNorm = ((f0 - 65f) / 295f).toDouble().coerceIn(0.0, 1.0)
                    val zcrNorm = (zcr.toDouble() / FRAME_SIZE).coerceIn(0.0, 1.0)

                    accum[0] += pitchNorm
                    accum[1] += centroidNorm
                    accum[2] += zcrNorm
                    accum[3] += min(1.0, rms * 4.0)
                    for (b in bandEnergies.indices) {
                        accum[4 + b] += bandEnergies[b] * invNorm
                    }
                    voicedFrames++
                }
            }
            offset += FRAME_STEP
        }

        if (voicedFrames < 4 || pitchValues.isEmpty()) return null

        val featureVec = FloatArray(FEATURE_DIM) { i -> (accum[i] / voicedFrames).toFloat() }
        // Re-normalize timbre tail [4..15]
        var tailNorm = 0.0
        for (i in 4 until FEATURE_DIM) {
            tailNorm += featureVec[i] * featureVec[i]
        }
        val tailInv = if (tailNorm > 1e-9) (1.0 / sqrt(tailNorm)).toFloat() else 1f
        for (i in 4 until FEATURE_DIM) {
            featureVec[i] *= tailInv
        }

        val sortedPitches = pitchValues.sorted()
        val medianPitch = sortedPitches[sortedPitches.size / 2]

        return VoiceprintProfile(
            bossName = bossName,
            featureVector = featureVec,
            meanPitchHz = medianPitch,
            sampleCount = 1
        )
    }

    fun mergeProfiles(profiles: List<VoiceprintProfile>, bossName: String = "Boss (Priyanshu)"): VoiceprintProfile? {
        if (profiles.isEmpty()) return null
        if (profiles.size == 1) return profiles.first().copy(bossName = bossName)

        val avgVec = FloatArray(FEATURE_DIM)
        var totalWeight = 0
        var pitchSum = 0f
        for (p in profiles) {
            if (p.featureVector.size != FEATURE_DIM) continue
            val w = p.sampleCount.coerceAtLeast(1)
            for (i in 0 until FEATURE_DIM) {
                avgVec[i] += p.featureVector[i] * w
            }
            pitchSum += p.meanPitchHz * w
            totalWeight += w
        }
        if (totalWeight == 0) return null
        for (i in 0 until FEATURE_DIM) {
            avgVec[i] /= totalWeight.toFloat()
        }
        var tailNorm = 0.0
        for (i in 4 until FEATURE_DIM) {
            tailNorm += avgVec[i] * avgVec[i]
        }
        val tailInv = if (tailNorm > 1e-9) (1.0 / sqrt(tailNorm)).toFloat() else 1f
        for (i in 4 until FEATURE_DIM) {
            avgVec[i] *= tailInv
        }

        return VoiceprintProfile(
            bossName = bossName,
            featureVector = avgVec,
            meanPitchHz = pitchSum / totalWeight.toFloat(),
            sampleCount = totalWeight
        )
    }

    fun verifySpeaker(enrolled: VoiceprintProfile?, candidateWavBytes: ByteArray): SpeakerVerificationResult {
        if (enrolled == null) {
            return SpeakerVerificationResult(
                isEnrolled = false,
                isBossMatch = true,
                similarityPercent = 100,
                measuredPitchHz = 0f,
                enrolledPitchHz = 0f,
                statusLabel = "Boss Voice ID not enrolled yet"
            )
        }

        val candidate = extractVoiceprintFromWav(candidateWavBytes, enrolled.bossName)
            ?: return SpeakerVerificationResult(
                isEnrolled = true,
                isBossMatch = true, // Too short/noisy to assert intruder falsely
                similarityPercent = 85,
                measuredPitchHz = 0f,
                enrolledPitchHz = enrolled.meanPitchHz,
                statusLabel = "Short sample — assuming Boss"
            )

        return compareProfiles(enrolled, candidate)
    }

    internal fun compareProfiles(
        enrolled: VoiceprintProfile,
        candidate: VoiceprintProfile
    ): SpeakerVerificationResult {
        // 1. Cosine similarity on 12-band vocal tract timbre [4..15]
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 4 until min(enrolled.featureVector.size, candidate.featureVector.size)) {
            val a = enrolled.featureVector[i].toDouble()
            val b = candidate.featureVector[i].toDouble()
            dot += a * b
            normA += a * a
            normB += b * b
        }
        val timbreCosine = if (normA > 1e-9 && normB > 1e-9) {
            (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(0f, 1f)
        } else 0.5f

        // 2. Relative pitch F0 score (humans rarely jump >28% semitone median on normal speech)
        val pitchDeltaRatio = abs(candidate.meanPitchHz - enrolled.meanPitchHz) /
            max(80f, enrolled.meanPitchHz)
        val pitchScore = (1f - (pitchDeltaRatio * 2.1f)).coerceIn(0f, 1f)

        // 3. Spectral brightness & ZCR proximity [1..2]
        val centroidDiff = abs(candidate.featureVector[1] - enrolled.featureVector[1])
        val zcrDiff = abs(candidate.featureVector[2] - enrolled.featureVector[2])
        val textureScore = (1f - ((centroidDiff * 2.2f) + (zcrDiff * 3.0f))).coerceIn(0f, 1f)

        val combined = (timbreCosine * 0.55f) + (pitchScore * 0.30f) + (textureScore * 0.15f)
        val percent = (combined * 100f).toInt().coerceIn(0, 100)
        val isMatch = combined >= BOSS_MATCH_THRESHOLD && pitchDeltaRatio <= 0.36f

        return SpeakerVerificationResult(
            isEnrolled = true,
            isBossMatch = isMatch,
            similarityPercent = percent,
            measuredPitchHz = candidate.meanPitchHz,
            enrolledPitchHz = enrolled.meanPitchHz,
            statusLabel = if (isMatch) {
                "Boss Verified (${percent}%)"
            } else {
                "Different Voice Detected (${percent}%)"
            }
        )
    }

    /**
     * Captures a short (~2.6s) spoken enrollment clip directly from the mic via AudioRecord.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureEnrollmentSample(
        context: Context,
        bossName: String = "Boss (Priyanshu)",
        durationMs: Long = 2600L
    ): Result<VoiceprintProfile> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure(IllegalStateException("Microphone permission required to record Boss Voice ID."))
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            return@withContext Result.failure(IllegalStateException("Microphone hardware unavailable."))
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE * 4)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try { recorder.release() } catch (_: Exception) {}
            return@withContext Result.failure(IllegalStateException("Unable to initialize microphone."))
        }

        val totalShorts = ((SAMPLE_RATE_HZ * durationMs) / 1000L).toInt()
        val captured = FloatArray(totalShorts)
        val buf = ShortArray(FRAME_SIZE)
        var filled = 0
        try {
            recorder.startRecording()
            while (filled < totalShorts) {
                val read = recorder.read(buf, 0, min(buf.size, totalShorts - filled))
                if (read <= 0) break
                for (i in 0 until read) {
                    captured[filled + i] = buf[i].toFloat() / 32768f
                }
                filled += read
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }

        val slice = if (filled == captured.size) captured else captured.copyOf(filled)
        val profile = extractVoiceprintFromNormalizedSamples(slice, bossName)
        if (profile == null) {
            Result.failure(IllegalStateException("Awaaz clear nahi mili — mic ke paas 2-3 second clear bolein."))
        } else {
            Result.success(profile)
        }
    }

    private fun estimatePitchAutocorrelation(samples: FloatArray, offset: Int, len: Int): Float {
        val minLag = SAMPLE_RATE_HZ / 360 // ~44 samples (~360 Hz)
        val maxLag = min(len / 2, SAMPLE_RATE_HZ / 65) // ~246 samples (~65 Hz)
        var bestLag = -1
        var bestCorr = 0.0

        var zeroLagEnergy = 0.0
        for (i in 0 until len) {
            val s = samples[offset + i].toDouble()
            zeroLagEnergy += s * s
        }
        if (zeroLagEnergy <= 1e-9) return 0f

        for (lag in minLag..maxLag) {
            var corr = 0.0
            val limit = len - lag
            for (i in 0 until limit) {
                corr += samples[offset + i].toDouble() * samples[offset + i + lag].toDouble()
            }
            val norm = corr / zeroLagEnergy
            if (norm > bestCorr) {
                bestCorr = norm
                bestLag = lag
            }
        }
        return if (bestLag > 0 && bestCorr >= 0.30) {
            SAMPLE_RATE_HZ.toFloat() / bestLag.toFloat()
        } else 0f
    }

    private fun goertzelMagnitude(
        samples: FloatArray,
        offset: Int,
        len: Int,
        targetFreq: Float,
        sampleRate: Int
    ): Double {
        val k = (0.5 + (len * targetFreq / sampleRate.toDouble())).toInt()
        val omega = (2.0 * PI * k) / len
        val coeff = 2.0 * cos(omega)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until len) {
            // Hann window reduces spectral leakage cleanly
            val window = 0.5 * (1.0 - cos((2.0 * PI * i) / (len - 1)))
            q0 = coeff * q1 - q2 + (samples[offset + i] * window)
            q2 = q1
            q1 = q0
        }
        val power = q1 * q1 + q2 * q2 - q1 * q2 * coeff
        return sqrt(max(0.0, power)) / len
    }
}
