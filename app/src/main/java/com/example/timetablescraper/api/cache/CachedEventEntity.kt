package com.example.timetablescraper.api.cache

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity that stores one cached timetable event.
 *
 * The composite lookup key is [courseIdentity] + [weekStart] (the Monday date).
 * A single fetch stores all events for that course+week together.
 */
@Entity(
    tableName = "cached_events",
    indices = [Index(value = ["courseIdentity", "weekStart"])]
)
data class CachedEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The course/programme identity string from the search result */
    val courseIdentity: String,

    /** The Monday date of the week, as "yyyy-MM-dd" */
    val weekStart: String,

    /** When this record was written (epoch millis) */
    val fetchedAt: Long,

    // ── Event fields ─────────────────────────────────────────────────

    val moduleCode: String,
    val title: String,
    val type: String,
    val lecturer: String,
    val room: String,

    /** ISO-8601 start, e.g. "2025-10-07T10:00:00" */
    val start: String,

    /** ISO-8601 end, e.g. "2025-10-07T12:00:00" */
    val end: String,

    /** Class group e.g. "TU859/Y3/C/G1" */
    val group: String = ""
)
