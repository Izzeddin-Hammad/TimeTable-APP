package com.example.timetablescraper.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Direct client for the Scientia Publish REST API.
 * No intermediate server needed — the API is public with Anonymous auth.
 */
object TimetableApiService {

    private const val API_BASE = "https://scientia-eu-v4-api-d4-01.azurewebsites.net/api/Public"
    private const val INSTITUTION_ID = "50a55ae1-1c87-4dea-bb73-c9e67941e1fd"
    private const val PROGRAMME_TYPE_ID = "241e4d36-93f2-4938-9e15-d4536fe3b2eb"
    private const val REFERER = "https://timetables.tudublin.ie/"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiHeaders = mapOf(
        "Authorization" to "Anonymous",
        "Referer" to REFERER,
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/plain, */*"
    )

    // ── Search ─────────────────────────────────────────────────────────

    suspend fun searchCourses(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val url = "$API_BASE/CategoryTypes/$PROGRAMME_TYPE_ID/Categories/FilterWithCache/$INSTITUTION_ID" +
                "?query=${query.replace(" ", "%20")}&pageNumber=1"

        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody(JSON_MEDIA))
            .apply { apiHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("API error ${response.code}: $body")

        val json = JSONObject(body)
        val resultsArray = json.getJSONArray("Results")
        val results = mutableListOf<SearchResult>()

        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.getJSONObject(i)
            val name = item.optString("Name", "")
            val progCode = if (name.contains("/")) name.substringBefore("/") else ""
            results.add(
                SearchResult(
                    name = name,
                    programme_code = progCode,
                    identity = item.optString("Identity", ""),
                    type = "Programme",
                    selection_id = "",
                    timetable_type_id = PROGRAMME_TYPE_ID
                )
            )
        }

        SearchResponse(results = results, count = results.size)
    }

    // ── Timetable ──────────────────────────────────────────────────────

    suspend fun fetchTimetable(
        categoryTypeId: String,
        identity: String,
        mondayDate: LocalDate
    ): TimetableResponse = withContext(Dispatchers.IO) {
        try {
            val sunday = mondayDate.plusDays(6)
            val startRange = mondayDate.minusDays(1).atTime(23, 0).atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", ".000Z")
            val endRange = sunday.atTime(23, 59, 59).atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", ".000Z")

            val url = "$API_BASE/CategoryTypes/Categories/Events/Filter/$INSTITUTION_ID" +
                    "?startRange=$startRange&endRange=$endRange"

            val body = buildEventsBody(categoryTypeId, identity, mondayDate)

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA))
                .apply { apiHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) throw Exception("API error ${response.code}")

            val json = JSONObject(respBody)
            val catEventsArray = json.optJSONArray("CategoryEvents") ?: JSONArray()
            val events = mutableListOf<ApiEvent>()

            for (i in 0 until catEventsArray.length()) {
                try {
                    val catEvent = catEventsArray.getJSONObject(i)
                    val resultsArray = catEvent.optJSONArray("Results") ?: JSONArray()
                    for (j in 0 until resultsArray.length()) {
                        try {
                            events.add(TimetableParser.parseApiEvent(resultsArray.getJSONObject(j)))
                        } catch (_: Exception) {
                            // Skip malformed individual events
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed category blocks
                }
            }

            TimetableResponse(events = events, source = "api", count = events.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Convert any fetch/parse failure into a clean exception
            throw Exception("Failed to load timetable for this week: ${e.message}", e)
        }
    }

    private fun buildEventsBody(categoryTypeId: String, identity: String, mondayDate: LocalDate): String {
        val sunday = mondayDate.plusDays(6)
        val startRange = mondayDate.minusDays(1).atTime(23, 0).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", ".000Z")
        val endRange = sunday.atTime(23, 59, 59).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", ".000Z")

        return JSONObject().apply {
            put("ViewOptions", JSONObject().apply {
                put("Days", JSONArray().apply {
                    for ((i, name) in listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday").withIndex()) {
                        put(JSONObject().apply {
                            put("Name", name); put("DayOfWeek", i + 1); put("IsDefault", true)
                        })
                    }
                })
                put("Weeks", JSONArray())
                put("TimePeriods", JSONArray().put(JSONObject().apply {
                    put("Description", "All Day"); put("StartTime", "00:00"); put("EndTime", "23:59"); put("IsDefault", true)
                }))
                put("DatePeriods", JSONArray().put(JSONObject().apply {
                    put("Description", "Current Range")
                    put("StartDateTime", startRange)
                    put("EndDateTime", endRange)
                    put("IsDefault", true)
                    put("Type", JSONObject.NULL)
                    put("Weeks", JSONArray())
                }))
            })
            put("CategoryTypesWithIdentities", JSONArray().put(JSONObject().apply {
                put("CategoryTypeIdentity", categoryTypeId)
                put("CategoryIdentities", JSONArray().put(identity))
            }))
            put("FetchBookings", false)
            put("FetchPersonalEvents", false)
            put("PersonalIdentities", JSONArray())
        }.toString()
    }

    fun getCurrentMonday(): LocalDate {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
