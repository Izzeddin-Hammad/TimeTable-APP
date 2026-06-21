package com.example.timetablescraper.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TimetableParser.parseApiEvent.
 *
 * These tests run on-device (androidTest) because they need org.json.JSONObject
 * from the Android SDK.
 */
class TimetableParserTest {

    // ── Helper: build a minimal API event JSON ──────────────────────────

    private fun buildEventJson(
        name: String,
        start: String = "2025-10-07T10:00:00",
        end: String = "2025-10-07T12:00:00",
        location: String = "A201",
        extraProperties: JSONArray? = null
    ): JSONObject = JSONObject().apply {
        put("Name", name)
        put("StartDateTime", start)
        put("EndDateTime", end)
        put("Location", location)
        if (extraProperties != null) put("ExtraProperties", extraProperties)
    }

    private fun extraProp(name: String, value: String): JSONObject = JSONObject().apply {
        put("Name", name)
        put("Value", value)
    }

    // ── Basic parsing ──────────────────────────────────────────────────

    @Test
    fun `parses module code from first name segment`() {
        val json = buildEventJson("TU859/Computer Science/Lec/Sem 1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", result.module_code)
    }

    @Test
    fun `parses title from second name segment`() {
        val json = buildEventJson("TU859/Software Engineering/Lec/Sem 1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Software Engineering", result.title)
    }

    @Test
    fun `parses type from segment before semester`() {
        val json = buildEventJson("TU859/Data Structures/Lab/Sem 2")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Lab", result.type)
    }

    @Test
    fun `parses tutorial type`() {
        val json = buildEventJson("TU858/Maths/Tut/Sem 1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Tut", result.type)
    }

    @Test
    fun `parses start and end times`() {
        val json = buildEventJson(
            name = "TU859/Networking/Lec/Sem 1",
            start = "2025-10-07T10:00:00",
            end = "2025-10-07T12:00:00"
        )
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("2025-10-07T10:00:00", result.start)
        assertEquals("2025-10-07T12:00:00", result.end)
    }

    // ── Group parsing from name ────────────────────────────────────────

    @Test
    fun `parses group from segment after semester`() {
        val json = buildEventJson("TU859/Computer Science/Lec/Sem 1/A")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("A", result.group)
    }

    @Test
    fun `parses G1 group after semester`() {
        val json = buildEventJson("TU859/Physics/Lab/Sem 1/G1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("G1", result.group)
    }

    @Test
    fun `empty group when no segment after semester`() {
        val json = buildEventJson("TU859/Chemistry/Lec/Sem 2")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("", result.group)
    }

    // ── Room parsing ───────────────────────────────────────────────────

    @Test
    fun `parses room location`() {
        val json = buildEventJson("TU859/Maths/Lec/Sem 1", location = "B305")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("B305", result.room)
    }

    @Test
    fun `strips floor number from room`() {
        val json = buildEventJson(
            "TU859/Database/Lec/Sem 1",
            location = "A201 (6)"
        )
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("A201", result.room)
    }

    @Test
    fun `handles empty location`() {
        val json = buildEventJson("TU859/AI/Lec/Sem 1", location = "")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("", result.room)
    }

    // ── ExtraProperties: Staff ─────────────────────────────────────────

    @Test
    fun `extracts lecturer from ExtraProperties Staff`() {
        val props = JSONArray().put(extraProp("Staff", "Dr. John Smith"))
        val json = buildEventJson("TU859/ML/Lec/Sem 1", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Dr. John Smith", result.lecturer)
    }

    @Test
    fun `lecturer empty when no Staff in ExtraProperties`() {
        val json = buildEventJson("TU859/ML/Lec/Sem 1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("", result.lecturer)
    }

    @Test
    fun `does not overwrite lecturer if already set`() {
        // Staff appears twice — only first is used
        val props = JSONArray()
            .put(extraProp("Staff", "Dr. First"))
            .put(extraProp("Staff", "Dr. Second"))
        val json = buildEventJson("TU859/OS/Lec/Sem 1", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Dr. First", result.lecturer)
    }

    // ── ExtraProperties: Module ────────────────────────────────────────

    @Test
    fun `extracts module code from ExtraProperties when name parsing fails`() {
        val props = JSONArray().put(extraProp("Module", "TU999"))
        // Single-segment name won't parse module code
        val json = buildEventJson("SingleSegmentName", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("TU999", result.module_code)
    }

    @Test
    fun `does not overwrite module code from ExtraProperties if already parsed`() {
        val props = JSONArray().put(extraProp("Module", "TU999"))
        val json = buildEventJson("TU858/Networking/Lec/Sem 1", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        // Name parsing wins
        assertEquals("TU858", result.module_code)
    }

    // ── ExtraProperties: Class Group ───────────────────────────────────

    @Test
    fun `extracts group from Class Group ExtraProperty`() {
        val props = JSONArray().put(extraProp("Class Group", "B"))
        val json = buildEventJson("TU859/Physics/Lab/Sem 1", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("B", result.group)
    }

    @Test
    fun `normalises multiple groups sorted alphabetically`() {
        val props = JSONArray().put(extraProp("Class Group", "G2 + G1"))
        val json = buildEventJson("TU859/Physics/Lab/Sem 1", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("G1 + G2", result.group)
    }

    @Test
    fun `name group wins over ExtraProperties group`() {
        val props = JSONArray().put(extraProp("Class Group", "B"))
        val json = buildEventJson("TU859/Database/Lab/Sem 1/A", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        // Group parsed from name ("A") should win since it's set first
        assertEquals("A", result.group)
    }

    @Test
    fun `handles empty Class Group value`() {
        val props = JSONArray().put(extraProp("Class Group", ""))
        val json = buildEventJson("TU859/Stats/Lec/Sem 1", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("", result.group)
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    fun `single segment name yields title as name`() {
        val json = buildEventJson("Holiday")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Holiday", result.title)
        assertEquals("", result.module_code)
        assertEquals("", result.type)
    }

    @Test
    fun `two segments without semester yields module and title only`() {
        val json = buildEventJson("TU859/Introduction to Programming")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", result.module_code)
        assertEquals("Introduction to Programming", result.title)
        assertEquals("", result.type)
        assertEquals("", result.group)
    }

    @Test
    fun `empty name yields empty fields`() {
        val json = buildEventJson("")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("", result.module_code)
        assertEquals("", result.title)
        assertEquals("", result.type)
        assertEquals("", result.group)
    }

    @Test
    fun `handles name with many slashes`() {
        val json = buildEventJson("TU859/Advanced Topics/Lec/Sem 2/G2/Extra")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("TU859", result.module_code)
        assertEquals("Advanced Topics", result.title)
        assertEquals("Lec", result.type)
        assertEquals("G2", result.group)
    }

    @Test
    fun `case-insensitive semester matching`() {
        val json = buildEventJson("TU858/Networking/Lab/sem 1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Lab", result.type)
    }

    @Test
    fun `semester with no space`() {
        val json = buildEventJson("TU859/Maths/Tut/Sem2")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("Tut", result.type)
    }

    @Test
    fun `missing start and end times default to empty`() {
        val json = buildEventJson("TU859/Maths/Lec/Sem 1", start = "", end = "")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("", result.start)
        assertEquals("", result.end)
    }

    @Test
    fun `handles all ExtraProperties simultaneously`() {
        val props = JSONArray()
            .put(extraProp("Staff", "Dr. Jane Doe"))
            .put(extraProp("Class Group", "C"))
            .put(extraProp("Module", "IGNORED")) // won't overwrite
        val json = buildEventJson("TU859/Graphics/Lab/Sem 1/A", extraProperties = props)
        val result = TimetableParser.parseApiEvent(json)

        assertEquals("TU859", result.module_code)
        assertEquals("Graphics", result.title)
        assertEquals("Lab", result.type)
        assertEquals("A", result.group) // name group wins
        assertEquals("Dr. Jane Doe", result.lecturer)
    }

    @Test
    fun `room with location number contains extra spaces`() {
        val json = buildEventJson("TU859/Stats/Lec/Sem 1", location = "  A201  (6)  ")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("A201", result.room)
    }

    @Test
    fun `module code in name with spaces`() {
        val json = buildEventJson("TU 859/Computer Science/Lec/Sem 1")
        val result = TimetableParser.parseApiEvent(json)
        assertEquals("TU 859", result.module_code)
    }
}
