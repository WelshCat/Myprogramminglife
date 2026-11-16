package io.github.welshcat.weekalarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * 알람 소리. 어떤 경우에도 소리가 나도록 여러 단계로 시도한다.
 *  1) 선택한 알람음  2) 기본 알람음  3) 기본 벨소리/알림음  4) 앱이 직접 만든 비프음 (파일 불필요)
 * 재생이 시작되지 않거나 도중에 오류가 나면 자동으로 다음 단계로 넘어간다.
 */
class Ringer(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var beep: AudioTrack? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null
    private var candidates = ArrayDeque<Uri>()
    private var volume = 1f
    private var gradual = false
    private var stopped = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(s: Settings, previewOnly: Boolean = false) {
        stopped = false
        raiseVolume(s.volumePercent)
        requestFocus()
        candidates = ArrayDeque(listOfNotNull(
            s.soundUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
            RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        ).distinct())
        gradual = s.gradualVolume && !previewOnly
        volume = if (gradual) 0.15f else 1f
        playNext()
        if (gradual) handler.postDelayed(ramp, 2000)
        if (s.vibrate && !previewOnly) vibrate()
    }

    private val ramp = object : Runnable {
        override fun run() {
            if (stopped) return
            volume = (volume + 0.06f).coerceAtMost(1f)       // 약 30초에 걸쳐 최대로
            try { player?.setVolume(volume, volume) } catch (_: Exception) {}
            try { beep?.setVolume(volume) } catch (_: Exception) {}
            if (volume < 1f) handler.postDelayed(this, 2000)
        }
    }

    private fun playNext() {
        if (stopped) return
        releasePlayer()
        val uri = candidates.removeFirstOrNull()
        if (uri == null) { playBeep(); return }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri)
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                Store.log(ctx, "알람음 재생 오류($what/$extra) → 다른 소리로 전환")
                handler.post { playNext() }
                true
            }
            mp.prepare()
            mp.setVolume(volume, volume)
            mp.start()
            player = mp
            // 2초 뒤에도 재생 중이 아니면 다음 소리로
            handler.postDelayed({
                if (!stopped && player === mp && !runCatching { mp.isPlaying }.getOrDefault(false)) {
                    Store.log(ctx, "알람음이 재생되지 않음 → 다른 소리로 전환")
                    playNext()
                }
            }, 2000)
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            if (player === mp) player = null
            Store.log(ctx, "알람음 열기 실패($uri): ${e.javaClass.simpleName} → 다른 소리로 전환")
            playNext()
        }
    }

    /** 최후의 수단: 파일 없이 직접 만든 삐- 삐- 소리. */
    private fun playBeep() {
        try {
            val rate = 44100
            val frames = rate                                   // 1초: 0.5초 소리 + 0.5초 쉼
            val buf = ShortArray(frames)
            for (i in 0 until frames / 2) {
                val env = if (i < 400) i / 400.0 else if (i > frames / 2 - 400) (frames / 2 - i) / 400.0 else 1.0
                buf[i] = (sin(2 * PI * 880 * i / rate) * env * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, frames)
            track.setLoopPoints(0, frames, -1)
            track.setVolume(volume)
            track.play()
            beep = track
            Store.log(ctx, "비상 비프음으로 재생")
        } catch (e: Exception) {
            Store.log(ctx, "비프음 재생 실패: $e")
        }
    }

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attrs)
            vibrator = v
        } catch (_: Exception) {}
    }

    private fun requestFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            audio.requestAudioFocus(req); focus = req
        } catch (_: Exception) {}
    }

    /** 울리는 동안만 알람 음량을 설정값으로 맞춘다 (휴대폰 알람 음량이 0이어도 울리도록). */
    private fun raiseVolume(percent: Int) = raiseVolumeStatic(ctx, percent)

    private fun releasePlayer() {
        player?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        player = null
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        beep?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        beep = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        focus?.let { try { audio.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        restoreVolumeIfNeeded(ctx)
    }

    companion object {
        /** 울리는 동안만 알람 음량을 설정값으로 맞춘다 (원래 값은 저장해 두었다가 복원). */
        fun raiseVolumeStatic(ctx: Context, percent: Int) {
            try {
                val audio = ctx.getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val target = ceil(max * percent.coerceIn(10, 100) / 100.0).toInt().coerceIn(1, max)
                val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                if (Store.savedAlarmVolume(ctx) < 0) Store.setSavedAlarmVolume(ctx, cur)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            } catch (e: Exception) {
                Store.log(ctx, "알람 음량 조정 실패: $e")
            }
        }

        fun restoreVolumeIfNeeded(ctx: Context) {
            val orig = Store.savedAlarmVolume(ctx)
            if (orig < 0 || RingState.current != null) return
            try { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, orig, 0) }
            catch (_: Exception) {}
            Store.setSavedAlarmVolume(ctx, -1)
        }
    }
}
