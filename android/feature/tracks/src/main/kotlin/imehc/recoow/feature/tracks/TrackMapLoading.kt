package imehc.recoow.feature.tracks

/** 只有样式真正加载失败才撤掉底图，网络慢时继续逐步填充。 */
internal data class TrackMapLoading(
    val styleReady: Boolean = false,
    val rendered: Boolean = false,
    val slow: Boolean = false,
    val routeOnly: Boolean = false,
    val problem: TrackMapProblem? = null,
) {
    val loading: Boolean get() = !rendered && !routeOnly
    fun styleLoaded() = copy(styleReady = true)
    fun frameRendered() = if (routeOnly) this else copy(rendered = true, slow = false, problem = null)
    fun timedOut() = if (rendered || routeOnly) this else copy(slow = true)
    fun failed(message: String) = copy(routeOnly = !styleReady, problem = TrackMapProblem.from(message))
}

internal enum class TrackMapProblem {
    DNS, NETWORK, SERVICE;

    companion object {
        fun from(message: String): TrackMapProblem {
            val text = message.lowercase()
            return when {
                listOf("unable to resolve host", "unknownhost", "no address associated", "could not resolve").any(text::contains) -> DNS
                listOf("network is unreachable", "no route to host", "failed to connect", "timeout", "timed out").any(text::contains) -> NETWORK
                else -> SERVICE
            }
        }
    }
}
