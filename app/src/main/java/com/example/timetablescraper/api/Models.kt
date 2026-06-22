package com.example.timetablescraper.api

import androidx.compose.runtime.Immutable

/**
 * Request body for POST /search/data
 */
data class SearchRequest(val query: String)

/**
 * A single search result from the server.
 */
@Immutable
data class SearchResult(
    val name: String,
    val programme_code: String,
    val identity: String,
    val type: String,
    val selection_id: String,
    val timetable_type_id: String
)

/**
 * Response wrapper for /search/data
 */
data class SearchResponse(
    val results: List<SearchResult>,
    val count: Int
)

/**
 * Request body for POST /timetable
 */
data class TimetableRequest(val url: String)

/**
 * A raw event as returned by the API.
 */
@Immutable
data class ApiEvent(
    val module_code: String,
    val title: String,
    val type: String,
    val lecturer: String,
    val room: String,
    val start: String,   // ISO-8601: "2025-10-07T10:00:00"
    val end: String,     // ISO-8601: "2025-10-07T12:00:00"
    val group: String = "",  // e.g. "A", "B", "G1" from the event name
    val id: Long = 0         // Room primary key (0 for network-fresh events)
)

/**
 * Response wrapper for /timetable
 */
data class TimetableResponse(
    val events: List<ApiEvent>,
    val source: String,
    val count: Int
)

/**
 * UI-ready event model with computed day and time range.
 */
@Immutable
data class TimetableEvent(
    val moduleCode: String,
    val title: String,
    val type: String,
    val lecturer: String,
    val room: String,
    val start: String,       // original ISO
    val end: String,         // original ISO
    val day: String,         // "Mon", "Tue", ...
    val dayIndex: Int,       // 0=Mon … 4=Fri
    val timeRange: String,   // "10:00 - 12:00"
    val weekStart: String,   // the Monday date this event belongs to
    val group: String = "",  // e.g. "A", "B", "G1" — sub-group identifier
    val id: Long = 0         // Room primary key — stable identity for LazyColumn
)
