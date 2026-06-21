package com.example.timetablescraper.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Configurable client for the Scientia Publish REST API.
 *
 * ## Zero hardcoded values
 * All endpoint UUIDs, base URLs, and identifiers are injected via
 * [InstitutionConfiguration]. The default config targets TU Dublin.
 *
 * ## Request minimization
 * - [RequestDebouncer] deduplicates concurrent requests to the same endpoint.
 * - Custom [userAgent] header identifies this as a student utility.
 * - No automatic retry — the repository handles backoff at the app layer.
 */
class TimetableApiService(
    private val config: InstitutionConfiguration = Institution.DEFAULT
) {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Shared HTTP client.  [retryOnConnectionFailure] is disabled so the
     *  app layer can control backoff (avoid silent 120s+ hangs on slow servers).
     *  [RateLimitInterceptor] enforces 5 req/10s to protect upstream server. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(RateLimitInterceptor(maxRequests = 5, perSeconds = 10))
        .build()

    private val headers: Map<String, String> = mapOf(
        "Authorization" to "Anonymous",
        "Referer"       to config.referer,
        "User-Agent"    to config.userAgent,
        "Content-Type"  to "application/json",
        "Accept"        to "application/json, text/plain, */*"
    )

    private val debouncer = RequestDebouncer.instance

    // ── Cached static parts of the request body (never change per request) ──
    /** Pre-built Days / TimePeriods / Weeks sections shared across all requests. */
    private val STATIC_VIEW_OPTIONS: JSONObject = JSONObject().apply {
        put("Days", JSONArray().apply {
            for ((i, name) in listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday").withIndex()) {
                put(JSONObject().apply {
                    put("Name", name); put("DayOfWeek", i + 1); put("IsDefault", true)
                })
            }
        })
        put("Weeks", JSONArray())
        put("TimePeriods", JSONArray().put(JSONObject().apply {
            put("Description", "All Day"); put("StartTime", "00:00")
            put("EndTime", "23:59"); put("IsDefault", true)
        }))
    }

    // ── Search ─────────────────────────────────────────────────────────

    suspend fun searchCourses(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val url = buildSearchUrl(query)

        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody(JSON_MEDIA))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response from search API")
            if (!response.isSuccessful)
                throw TimetableApiException.fromResponse(response.code, body)
            parseSearchResponse(body)
        }
    }

    // ── Timetable ──────────────────────────────────────────────────────

    suspend fun fetchTimetable(
        categoryTypeId: String,
        identity: String,
        mondayDate: LocalDate
    ): TimetableResponse = withContext(Dispatchers.IO) {

        val debounceKey = buildDebounceKey(categoryTypeId, identity, mondayDate)

        debouncer.execute(debounceKey) {
            val bodyJson = buildEventsRequestBody(categoryTypeId, identity, mondayDate)
            val url = buildEventsUrl(mondayDate)

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                    ?: throw Exception("Empty response from timetable API")
                if (!response.isSuccessful)
                    throw TimetableApiException.fromResponse(response.code, respBody)
                parseTimetableResponse(respBody)
            }
        }
    }

    // ── URL building ──────────────────────────────────────────────────

    private fun buildSearchUrl(query: String): String {
        // Properly URL-encode the query to handle special characters
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "${config.apiBaseUrl}/CategoryTypes/${config.programmeTypeId}" +
                "/Categories/FilterWithCache/${config.institutionId}" +
                "?query=$encoded&pageNumber=1"
    }

    private fun buildEventsUrl(mondayDate: LocalDate): String {
        val startRange = formatRangeStart(mondayDate)
        val endRange = formatRangeEnd(mondayDate.plusDays(6))
        return "${config.apiBaseUrl}/CategoryTypes/Categories/Events/Filter/${config.institutionId}" +
                "?startRange=$startRange&endRange=$endRange"
    }

    // ── Request body (reuses cached static parts) ─────────────────────

    private fun buildEventsRequestBody(
        categoryTypeId: String,
        identity: String,
        mondayDate: LocalDate
    ): String {
        val sunday = mondayDate.plusDays(6)

        return JSONObject().apply {
            put("ViewOptions", cloneViewOptions(mondayDate, sunday))
            put("CategoryTypesWithIdentities", JSONArray().put(JSONObject().apply {
                put("CategoryTypeIdentity", categoryTypeId)
                put("CategoryIdentities", JSONArray().put(identity))
            }))
            put("FetchBookings", false)
            put("FetchPersonalEvents", false)
            put("PersonalIdentities", JSONArray())
        }.toString()
    }

    /** Shallow-clone the static ViewOptions and inject the dynamic DatePeriod. */
    private fun cloneViewOptions(monday: LocalDate, sunday: LocalDate): JSONObject {
        val vo = JSONObject()
        STATIC_VIEW_OPTIONS.keys().forEach { key ->
            vo.put(key, STATIC_VIEW_OPTIONS.get(key))
        }
        vo.put("DatePeriods", JSONArray().put(JSONObject().apply {
            put("Description", "Current Range")
            put("StartDateTime", formatRangeStart(monday))
            put("EndDateTime", formatRangeEnd(sunday))
            put("IsDefault", true)
            put("Type", JSONObject.NULL)
            put("Weeks", JSONArray())
        }))
        return vo
    }

    // ── Debounce key ──────────────────────────────────────────────────

    private fun buildDebounceKey(
        categoryTypeId: String,
        identity: String,
        mondayDate: LocalDate
    ): String = "POST|timetable|${config.institutionId}|$categoryTypeId|$identity|" +
            mondayDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

    // ── Date formatting ───────────────────────────────────────────────

    private fun formatRangeStart(mondayDate: LocalDate): String {
        return mondayDate.minusDays(1).atTime(23, 0).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", ".000Z")
    }

    private fun formatRangeEnd(sunday: LocalDate): String {
        return sunday.atTime(23, 59, 59).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", ".000Z")
    }

    // ── Response parsing ──────────────────────────────────────────────

    private fun parseSearchResponse(body: String): SearchResponse {
        val json = JSONObject(body)
        val resultsArray = json.getJSONArray("Results")
        val results = mutableListOf<SearchResult>()

        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.getJSONObject(i)
            val name = item.optString("Name", "")
            val progCode = if (name.contains("/")) name.substringBefore("/") else ""
            results.add(SearchResult(
                name = name, programme_code = progCode,
                identity = item.optString("Identity", ""),
                type = "Programme", selection_id = "",
                timetable_type_id = config.programmeTypeId
            ))
        }
        return SearchResponse(results = results, count = results.size)
    }

    private fun parseTimetableResponse(body: String): TimetableResponse {
        val json = JSONObject(body)
        val catEventsArray = json.optJSONArray("CategoryEvents") ?: JSONArray()
        val events = mutableListOf<ApiEvent>()

        for (i in 0 until catEventsArray.length()) {
            try {
                val catEvent = catEventsArray.getJSONObject(i)
                val resultsArray = catEvent.optJSONArray("Results") ?: JSONArray()
                for (j in 0 until resultsArray.length()) {
                    try {
                        events.add(TimetableParser.parseApiEvent(resultsArray.getJSONObject(j)))
                    } catch (_: Exception) { /* skip malformed event */ }
                }
            } catch (_: Exception) { /* skip malformed block */ }
        }
        return TimetableResponse(events = events, source = "api", count = events.size)
    }

    // ── Static helpers ────────────────────────────────────────────────

    companion object {
        val DEFAULT: TimetableApiService by lazy { TimetableApiService() }

        fun getCurrentMonday(): LocalDate {
            return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }
    }
}
