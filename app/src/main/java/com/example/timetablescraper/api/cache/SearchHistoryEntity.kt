package com.example.timetablescraper.api.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores recent search queries for quick re-access.
 * Deduplicated by query text — re-searching the same term updates the timestamp.
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    val query: String,

    /** Epoch millis of the most recent search for this query. */
    val searchedAt: Long
)
