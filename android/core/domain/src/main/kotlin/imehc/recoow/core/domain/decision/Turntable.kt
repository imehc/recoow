package imehc.recoow.core.domain.decision

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

fun normalizedDecisionTitle(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC).trim().split(Regex("[\\s\\p{Z}]+" )).joinToString(" ").lowercase(Locale.ROOT)
data class TurntablePlacement(val slot: Int, val index: Int, val angle: Double, val radius: Double, val scale: Double)
object Turntable {
    const val LIMIT = 8
    fun wrap(index: Int, count: Int) = if (count == 0) 0 else Math.floorMod(index, count)
    private fun nearest(value: Double) = if (value >= 0) floor(value + .5) else ceil(value - .5)
    fun focused(cursor: Double, count: Int) = if (count == 0) null else wrap(nearest(cursor).toInt(), count)
    fun placements(count: Int, cursor: Double): List<TurntablePlacement> {
        val visible = min(count, LIMIT); if (visible == 0) return emptyList()
        val virtual = count > visible; val first = if (virtual) floor(cursor).toInt() - (visible - 1) / 2 else 0
        return (0 until visible).map { offset ->
            val logical = first + offset; val angle = if (count == 1) 0.0 else (logical - cursor) * 2 * PI / visible
            val seam = ((PI - abs(atan2(sin(angle), cos(angle)))) / .32).coerceIn(0.0, 1.0)
            TurntablePlacement(if (virtual) wrap(logical, visible) else offset, wrap(logical, count), angle, 1.5,
                if (virtual) seam * seam * (3 - 2 * seam) else 1.0)
        }
    }
    fun browse(index: Int, count: Int, cursor: Double): Double {
        if (count < 1) return 0.0
        val delta = index - cursor; return cursor + delta - nearest(delta / count) * count
    }
    fun draw(index: Int, count: Int, cursor: Double): Double {
        if (count < 2) return index.toDouble()
        var target = index + ceil((cursor - index) / count) * count
        while (target - cursor < min(count, LIMIT) * 2.2) target += count
        return target
    }
}
