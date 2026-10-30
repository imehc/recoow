package imehc.recoow.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import imehc.recoow.core.domain.media.PreparedMedia
import java.io.File

/** Foreground-only, composition-owned recorder. Every stop, failure, focus loss and disposal releases it. */
class AudioRecorder(private val context: Context, private val onInterrupted: () -> Unit) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var start = 0L
    private val manager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    val elapsedSeconds get() = if (recorder == null) 0 else (SystemClock.elapsedRealtime() - start) / 1000
    val recording get() = recorder != null

    fun start() {
        cancel()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change < 0 && recording) { cancel(); onInterrupted() } }.build()
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus = request
        try {
            val directory = File(context.cacheDir, "media-drafts").apply { mkdirs() }
            val output = File.createTempFile("audio-", ".m4a", directory); file = output
            @Suppress("DEPRECATION")
            val current = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = current
            current.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1); setAudioSamplingRate(44100); setAudioEncodingBitRate(128000)
                setMaxFileSize(64L * 1024 * 1024)
                setOutputFile(output.path)
                setOnErrorListener { _, _, _ -> cancel(); onInterrupted() }
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) { cancel(); onInterrupted() } }
                prepare(); start()
            }
            start = SystemClock.elapsedRealtime()
        } catch (error: Throwable) { cancel(); throw error }
    }

    fun finish(): PreparedMedia {
        val current = checkNotNull(recorder); val output = checkNotNull(file)
        try {
            current.stop(); current.release(); recorder = null; abandonFocus()
            val metadata = MediaMetadataRetriever()
            val duration = try {
                metadata.setDataSource(output.path)
                checkNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()) / 1000.0
            } finally { metadata.release() }
            check(output.length() > 0 && duration > 0)
            file = null
            return PreparedMedia(output, "audio/mp4", durationSeconds = duration)
        } catch (error: Throwable) { cancel(); throw error }
    }
    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null
        file?.delete(); file = null; abandonFocus()
    }
    private fun abandonFocus() { focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null }
}
