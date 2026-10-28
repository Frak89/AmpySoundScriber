package org.ampy.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.ampy.audioengine.PcmSource
import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lettore multitraccia: mixa in tempo reale più sorgenti PCM (stem, registrazioni, ricostruzioni MIDI)
 * applicando volume, pan, mute e solo modificabili durante la riproduzione.
 * La posizione riportata compensa la latenza di AudioTrack (per il cursore sincronizzato dello spartito).
 */
class AudioPlayerEngine(private val context: Context) {

    class Channel(val id: String, val source: PcmSource) {
        @Volatile var volume = 1f
        @Volatile var pan = 0f
        @Volatile var muted = false
        @Volatile var solo = false
    }

    private val channels = CopyOnWriteArrayList<Channel>()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var track: AudioTrack? = null
    @Volatile private var startFrame = 0L
    @Volatile var sampleRate = 44100; private set
    private var focusRequest: AudioFocusRequest? = null

    val isPlaying get() = running.get()

    fun setChannels(list: List<Channel>) {
        val wasPlaying = isPlaying; val pos = positionFrames()
        if (wasPlaying) stop()
        channels.clear(); channels.addAll(list)
        sampleRate = list.firstOrNull()?.source?.sampleRate ?: 44100
        startFrame = pos
        if (wasPlaying) play(pos)
    }

    fun channel(id: String) = channels.firstOrNull { it.id == id }

    val durationFrames: Long get() = channels.maxOfOrNull { it.source.frameCount } ?: 0L

    /** Posizione di ascolto in frame (stima: testina di AudioTrack + offset di partenza). */
    fun positionFrames(): Long {
        val t = track ?: return startFrame
        return startFrame + (runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L))
    }

    fun positionSec(): Double = positionFrames().toDouble() / sampleRate

    fun play(fromFrame: Long = startFrame) {
        if (channels.isEmpty() || !running.compareAndSet(false, true)) return
        requestFocus()
        startFrame = fromFrame.coerceIn(0, durationFrames)
        val sr = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, 8192 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        worker = thread(name = "ampy-player", priority = Thread.MAX_PRIORITY) {
            val block = 2048
            val mix = FloatArray(block * 2)
            var pos = startFrame
            try {
                t.play()
                while (running.get() && pos < durationFrames) {
                    java.util.Arrays.fill(mix, 0f)
                    val anySolo = channels.any { it.solo }
                    var produced = 0
                    for (c in channels) {
                        if (c.muted || (anySolo && !c.solo)) continue
                        val ch = c.source.readChannels(pos, block)
                        val n = ch.firstOrNull()?.size ?: 0
                        if (n == 0) continue
                        produced = maxOf(produced, n)
                        val angle = (c.pan.coerceIn(-1f, 1f) + 1) * PI / 4
                        val gl = (cos(angle) * 1.4142).toFloat() * c.volume; val gr = (sin(angle) * 1.4142).toFloat() * c.volume
                        val left = ch[0]; val right = if (ch.size > 1) ch[1] else ch[0]
                        for (i in 0 until n) { mix[2 * i] += left[i] * gl; mix[2 * i + 1] += right[i] * gr }
                    }
                    val frames = if (produced == 0) block else produced
                    for (i in 0 until frames * 2) mix[i] = mix[i].coerceIn(-1f, 1f)
                    val w = t.write(mix, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { AmpyLog.e(LogCategory.SYNC, "player", "AudioTrack.write errore $w"); break }
                    pos += frames
                }
                // svuota il buffer prima di fermarsi alla fine del brano
                if (running.get()) { t.stop(); }
            } catch (e: Exception) {
                AmpyLog.e(LogCategory.SYNC, "player", "Riproduzione interrotta: ${e.message}", e)
            } finally {
                running.set(false)
            }
        }
    }

    fun pause() {
        val pos = positionFrames()
        stop()
        startFrame = pos
    }

    fun seek(sec: Double) {
        val f = (sec * sampleRate).toLong()
        if (isPlaying) { stop(); play(f) } else startFrame = f.coerceIn(0, durationFrames)
    }

    fun stop() {
        running.set(false)
        track?.let { runCatching { it.pause(); it.flush() } }
        worker?.join(1000); worker = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        abandonFocus()
    }

    fun release() { stop(); channels.clear() }

    private fun requestFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }
            .build()
        val r = am.requestAudioFocus(req)
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) AmpyLog.w(LogCategory.SYNC, "player", "Focus audio non concesso ($r)")
        focusRequest = req
    }

    private fun abandonFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Latenza di uscita stimata in ms (riportata nella diagnostica per gli errori di sincronizzazione). */
    fun estimatedLatencyMs(): Double {
        val t = track ?: return 0.0
        return t.bufferSizeInFrames * 1000.0 / sampleRate
    }
}
