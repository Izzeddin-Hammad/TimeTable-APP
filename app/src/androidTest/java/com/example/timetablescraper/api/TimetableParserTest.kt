package com.example.timetablescraper.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for TimetableParser (requires Android for org.json).
 *
 * Covers the chaos-engineering edge cases from the v1.9 audit:
 * - Missing JSON keys
 * - Null field values
 * - Malformed event name patterns
 * - ExtraProperties parsing
 */
@RunWith(AndroidJUnit4::class)
class TimetableParserTest {

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    fun parses_a_complete_event_with_all_fields() {
        val json = JSONObject().apply {
            put("StartDateTime", "2025-10-07T10:00:00")
            put("EndDateTime", "2025-10-07T12:00:00")
            put("Name", "TU859/Algorithms/Lec/Sem 1/A")
            put("Location", "A201 (123)")
            put("ExtraProperties", JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Staff")
                    put("Value", "Dr. Smith")
                })
                put(JSONObject().apply {
                    put("Name", "Class Group")
                    put("Value", "G2 + G1")
                })
            })
        }

        val event = TimetableParser.parseApiEvent(json)

        assertEquals("TU859", event.module_code)
        assertEquals("Algorithms", event.title)
        assertEquals("Lec", event.type)
        assertEquals("Dr. Smith", event.lecturer)
        assertEquals("A201", event.room)
        assertEquals("2025-10-07T10:00:00", event.start)
        assertEquals("2025-10-07T12:00:00", event.end)
        assertEquals("G1 + G2", event.group) // normalised alphabetical
    }

    // ── Missing keys (JSON schema mutation) ─────────────────────────────

    @Test
    fun missing_StartDateTime_defaults_to_empty_string() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1/A")
            put("Location", "Room")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("", event.start)
        assertEquals("", event.end)
    }

    @Test
    fun missing_Name_field_defaults_to_empty_string() {
        val json = JSONObject().apply {
            put("Location", "Room")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("", event.module_code)
        assertEquals("", event.title)
    }

    @Test
    fun missing_ExtraProperties_still_parses_event() {
        val json = JSONObject().apply {
            put("StartDateTime", "2025-10-07T10:00:00")
            put("EndDateTime", "2025-10-07T12:00:00")
            put("Name", "TU859/Algorithms/Lec/Sem 1/A")
            put("Location", "A201")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", event.module_code)
        assertEquals("Algorithms", event.title)
        assertEquals("Lec", event.type)
        assertEquals("A201", event.room)
        assertEquals("", event.lecturer)
    }

    @Test
    fun missing_Location_defaults_to_empty_string() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1/A")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("", event.room)
    }

    // ── Null values (null injection) ────────────────────────────────────

    @Test
    fun null_Location_becomes_empty_string() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1/A")
            put("Location", JSONObject.NULL)
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("", event.room)
    }

    @Test
    fun null_Name_becomes_empty_string() {
        val json = JSONObject().apply {
            put("Name", JSONObject.NULL)
            put("Location", "Room")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("", event.module_code)
        assertEquals("", event.title)
    }

    // ── Malformed event name patterns ───────────────────────────────────

    @Test
    fun event_name_with_no_slashes() {
        val json = JSONObject().apply {
            put("Name", "TU859Algorithms")
            put("Location", "Room")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("", event.module_code)
        assertEquals("TU859Algorithms", event.title)
    }

    @Test
    fun event_name_with_only_one_slash() {
        val json = JSONObject().apply {
            put("Name", "TU859/Algorithms")
            put("Location", "Room")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", event.module_code)
        assertEquals("Algorithms", event.title)
        assertEquals("", event.type)
        assertEquals("", event.group)
    }

    @Test
    fun event_name_with_type_but_no_group() {
        val json = JSONObject().apply {
            put("Name", "TU859/Algorithms/Lec/Sem 1")
            put("Location", "Room")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", event.module_code)
        assertEquals("Algorithms", event.title)
        assertEquals("Lec", event.type)
        assertEquals("", event.group)
    }

    @Test
    fun event_name_with_type_and_group() {
        val json = JSONObject().apply {
            put("Name", "TU859/Algorithms/Lec/Sem 1/A")
            put("Location", "A201")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("A", event.group)
    }

    @Test
    fun event_name_with_multi_segment_name() {
        val json = JSONObject().apply {
            put("Name", "TU859/Computer Science 101/Lab/Sem 2/G1")
            put("Location", "Lab A")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", event.module_code)
        assertEquals("Computer Science 101", event.title)
        assertEquals("Lab", event.type)
        assertEquals("G1", event.group)
    }

    // ── ExtraProperties override ─────────────────────────────────────────

    @Test
    fun ExtraProperties_Staff_overrides_lecturer() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1")
            put("Location", "Room")
            put("ExtraProperties", JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Staff")
                    put("Value", "Prof. Override")
                })
            })
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("Prof. Override", event.lecturer)
    }

    @Test
    fun ExtraProperties_Module_overrides_module_code_when_empty() {
        val json = JSONObject().apply {
            put("Name", "Subject/Lec/Sem 1/A")
            put("Location", "Room")
            put("ExtraProperties", JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Module")
                    put("Value", "TU999")
                })
            })
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("TU999", event.module_code)
    }

    @Test
    fun ExtraProperties_Class_Group_normalises_compound_groups() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1")
            put("Location", "Room")
            put("ExtraProperties", JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Class Group")
                    put("Value", "C + A + B")
                })
            })
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("A + B + C", event.group)
    }

    // ── Room location cleanup ───────────────────────────────────────────

    @Test
    fun Location_brackets_are_stripped() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1/A")
            put("Location", "A201 (123)")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("A201", event.room)
    }

    @Test
    fun Location_without_brackets_stays_unchanged() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1/A")
            put("Location", "Main Hall")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("Main Hall", event.room)
    }

    @Test
    fun Location_with_only_closing_bracket_number() {
        val json = JSONObject().apply {
            put("Name", "TU859/Title/Lec/Sem 1/A")
            put("Location", "Room B (42)")
        }
        val event = TimetableParser.parseApiEvent(json)
        assertEquals("Room B", event.room)
    }
}
