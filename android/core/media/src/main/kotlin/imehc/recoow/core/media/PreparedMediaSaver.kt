package imehc.recoow.core.media

import androidx.compose.runtime.saveable.Saver
import imehc.recoow.core.domain.media.PreparedMedia
import java.io.File

/** 只保存私有草稿的路径与元数据，绝不把图片或音频字节放进 Activity Bundle。 */
val PreparedMediaSaver: Saver<PreparedMedia?, Any> = Saver(
    save = { value -> value?.let { listOf(it.file.path, it.mimeType, it.width ?: 0, it.height ?: 0, it.durationSeconds ?: -1.0) } ?: "" },
    restore = { saved ->
        (saved as? List<*>)?.let {
            PreparedMedia(File(it[0] as String), it[1] as String, (it[2] as Int).takeIf { value -> value > 0 },
                (it[3] as Int).takeIf { value -> value > 0 }, (it[4] as Double).takeIf { value -> value > 0 })
        }
    },
)
