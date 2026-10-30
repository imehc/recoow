package imehc.recoow.core.media

import androidx.compose.runtime.saveable.Saver
import imehc.recoow.core.domain.media.PreparedMedia
import java.io.File

/** Save only a private draft's path and metadata, never image/audio bytes in an Activity Bundle. */
val PreparedMediaSaver: Saver<PreparedMedia?, Any> = Saver(
    save = { value -> value?.let { listOf(it.file.path, it.mimeType, it.width ?: 0, it.height ?: 0, it.durationSeconds ?: -1.0) } ?: "" },
    restore = { saved ->
        (saved as? List<*>)?.let {
            PreparedMedia(File(it[0] as String), it[1] as String, (it[2] as Int).takeIf { value -> value > 0 },
                (it[3] as Int).takeIf { value -> value > 0 }, (it[4] as Double).takeIf { value -> value > 0 })
        }
    },
)
