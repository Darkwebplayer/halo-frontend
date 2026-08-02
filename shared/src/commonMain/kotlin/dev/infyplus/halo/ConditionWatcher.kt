package dev.infyplus.halo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OpenMeteoResponse(val current: OpenMeteoCurrent? = null)

@Serializable
private data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperature: Double? = null,
    val precipitation: Double? = null,
)

/** Current readings, keyed by the metric names the server uses. */
data class Readings(val temperature: Double?, val precipitation: Double?) {
    fun valueOf(metric: String): Double? = when (metric) {
        "temperature" -> temperature
        "precipitation" -> precipitation
        else -> null
    }
}

/**
 * Evaluates weather-conditioned reminders on the device.
 *
 * This lives client-side because open-meteo needs no API key — there is nothing secret to
 * protect, so routing it through the server would only add a dependency. The 15-minute tick
 * each platform provides matches the cadence the server cron used, so nothing is lost.
 */
object ConditionWatcher {

    /**
     * Timeouts, because [Sync.loop] awaits this.
     *
     * Without them a hung socket to open-meteo stalls the one-minute loop that arms every reminder
     * on the device — a weather lookup taking the schedule down with it. Shorter than the app's own
     * client: this is a nice-to-have reading, not the user's data.
     */
    private val client by lazy {
        HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }

    /** Throws on a failed fetch. A caller must not treat "couldn't check" as "condition false". */
    suspend fun read(location: GeoPoint): Readings = withContext(Dispatchers.Default) {
        val body: OpenMeteoResponse = client
            .get(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${location.lat}&longitude=${location.lon}" +
                    "&current=temperature_2m,precipitation",
            )
            .body()
        val current = body.current ?: throw IllegalStateException("open-meteo returned no readings")
        Readings(current.temperature, current.precipitation)
    }

    fun isMet(condition: WeatherCondition, readings: Readings): Boolean {
        val value = readings.valueOf(condition.metric) ?: return false
        return if (condition.op == "gt") value > condition.threshold else value < condition.threshold
    }

    /**
     * Which of [items] should fire now.
     *
     * A fetch failure deliberately propagates rather than returning an empty list — silently
     * deciding "not raining" because the network was down is the worst possible outcome here.
     */
    suspend fun due(items: List<Scheduled>, location: GeoPoint): List<Scheduled> {
        val watched = items.filter { it.condition != null }
        if (watched.isEmpty()) return emptyList()
        val readings = read(location)
        return watched.filter { isMet(it.condition!!, readings) }
    }
}
