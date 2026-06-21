package com.example.timetablescraper.api

import org.json.JSONObject

/**
 * Extracted parser for Scientia API event JSON objects.
 * Separated from TimetableApiService so it can be unit-tested directly.
 */
internal object TimetableParser {

    /**
     * Parse a single event JSON object from the API response into an [ApiEvent].
     *
     * The event name follows the pattern:
     *   "MODULE_CODE/Subject Name/Lec/Sem 1/A" or similar variations.
     *
     * ExtraProperties may contain additional fields like Staff, Module, Class Group.
     */
    fun parseApiEvent(ev: JSONObject): ApiEvent {
        val start = ev.optString("StartDateTime", "")
        val end = ev.optString("EndDateTime", "")
        val name = ev.optString("Name", "")
        val parts = name.split("/")

        var moduleCode = ""
        var title = name
        var type = ""
        var group = ""

        if (parts.size >= 2) {
            val codeMatch = Regex("""(\w[\w\s]*?\d+)""").find(parts[0])
            if (codeMatch != null) moduleCode = codeMatch.value.trim()
            // Title: the second segment is always the subject name
            title = parts[1].trim()

            // Find the semester marker (Sem1/Sem2) — it's always near the end
            val semIndex = parts.indexOfLast { it.trim().matches(Regex("""(?i)Sem\s*\d""")) }

            // Type is the segment right before the semester
            if (semIndex > 1) {
                type = parts[semIndex - 1].trim()
            }

            // Group is the segment after the semester (if any), e.g. "A", "B", "G1"
            group = if (semIndex in 1 until parts.size - 1) {
                parts[semIndex + 1].trim()
            } else ""
        }

        var lecturer = ""
        val extraProps = ev.optJSONArray("ExtraProperties")
        if (extraProps != null) {
            for (k in 0 until extraProps.length()) {
                val prop = extraProps.getJSONObject(k)
                when (prop.optString("Name", "")) {
                    "Staff" -> if (lecturer.isEmpty()) lecturer = prop.optString("Value", "")
                    "Module" -> if (moduleCode.isEmpty()) {
                        val mc = Regex("""(\w[\w\s]*?\d+)""").find(prop.optString("Value", ""))
                        if (mc != null) moduleCode = mc.value.trim()
                    }
                    "Class Group" -> if (group.isEmpty()) {
                        // Normalize: sort groups alphabetically so "G2 + G1" = "G1 + G2"
                        val raw = prop.optString("Value", "").trim()
                        group = raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(" + ")
                    }
                }
            }
        }

        var room = ev.optString("Location", "").replace(Regex("""\s*\(\d+\)$"""), "").trim()

        return ApiEvent(
            module_code = moduleCode.trim(), title = title.trim(), type = type.trim(),
            lecturer = lecturer.trim(), room = room.trim(), start = start.trim(), end = end.trim(),
            group = group.trim()
        )
    }
}
