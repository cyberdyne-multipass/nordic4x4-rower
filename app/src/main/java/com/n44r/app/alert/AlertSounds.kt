package com.n44r.app.alert

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthesised warning sounds, deliberately different so they can be told apart without
 * looking at the tablet:
 *  - heart rate lost:   two short HIGH beeps            ("piep-piep")
 *  - rower data lost:   two long LOW tones              ("tuuut - tuuut")
 *  - signal back:       short rising two-tone chirp
 * Played via the media stream (tablet media volume).
 */
object AlertSounds {
    private const val RATE = 22050

    fun heartRateLost() = play(listOf(1760 to 130, 0 to 110, 1760 to 130))
    fun rowerLost() = play(listOf(392 to 550, 0 to 200, 330 to 750))
    fun recovered() = play(listOf(880 to 90, 1320 to 140))

    /** (frequencyHz to durationMs) segments; frequency 0 = silence. */
    private fun play(segments: List<Pair<Int, Int>>) {
        thread(name = "alert-sound", isDaemon = true) {
            runCatching {
                val total = segments.sumOf { it.second } * RATE / 1000
                val pcm = ShortArray(total)
                var pos = 0
                for ((freq, ms) in segments) {
                    val n = ms * RATE / 1000
                    val fade = min(n / 6, RATE / 200) // 5 ms ramps, no clicks
                    for (i in 0 until n) {
                        if (pos >= total) break
                        val env = when {
                            i < fade -> i.toFloat() / fade
                            i > n - fade -> (n - i).toFloat() / fade
                            else -> 1f
                        }
                        pcm[pos++] = if (freq == 0) 0 else (sin(2 * PI * freq * i / RATE) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(segments.sumOf { it.second }.toLong() + 150)
                track.release()
            }
        }
    }
}
