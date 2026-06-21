package com.example.timetablescraper.api.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A course that the user has explicitly saved/bookmarked
 * for quick access from the settings screen.
 */
@Entity(tableName = "saved_courses")
data class SavedCourseEntity(
    @PrimaryKey
    val identity: String,

    /** Display name, e.g. "TU859/Computer Science" */
    val name: String,

    /** Programme code, e.g. "TU859" */
    val programmeCode: String,

    /** The timetable type ID needed for API queries */
    val timetableTypeId: String,

    /** When the course was saved (epoch millis) */
    val savedAt: Long,

    /** Pre-selected group, e.g. "TU859/Y3/MLAI/G2" — null if no group was selected */
    val group: String? = null
)
