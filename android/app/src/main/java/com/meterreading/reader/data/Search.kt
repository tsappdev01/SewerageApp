package com.meterreading.reader.data

enum class DoneFilter { ALL, TO_READ, DONE }

data class SearchQuery(
    val text: String = "",
    val done: DoneFilter = DoneFilter.ALL,
    /** Null shows both types. */
    val type: MeterType? = null,
    /** Limits the search to one zone; null searches all assigned zones. */
    val zoneCode: String? = null,
)

data class SearchResults(val properties: List<PropertyProgress>, val meters: List<Meter>) {
    val isEmpty: Boolean get() = properties.isEmpty() && meters.isEmpty()
}

/**
 * Finds a property by its code, name or tenant company, or a meter by its number.
 * Matching ignores case, spaces and dashes, so "1499w1", "1499 W1" and "1499-w1" all
 * find 1499-W1. Readers type a few digits; a match anywhere in the code counts.
 */
object Search {
    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.uppercase()

    fun matches(candidate: String, query: String): Boolean {
        val q = normalize(query)
        return q.isEmpty() || normalize(candidate).contains(q)
    }

    /** Range in [display] to highlight for [query], or null if it does not match. */
    fun highlightRange(display: String, query: String): IntRange? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val kept = display.indices.filter { display[it].isLetterOrDigit() }
        val normalized = kept.joinToString("") { display[it].uppercaseChar().toString() }
        val start = normalized.indexOf(q)
        if (start < 0) return null
        return kept[start]..kept[start + q.length - 1]
    }

    fun run(query: SearchQuery, properties: List<PropertyProgress>): SearchResults {
        fun keep(m: Meter): Boolean {
            val doneOk = when (query.done) {
                DoneFilter.ALL -> true
                DoneFilter.TO_READ -> m.state.canCapture
                DoneFilter.DONE -> !m.state.canCapture
            }
            return doneOk && (query.type == null || m.type == query.type)
        }

        val inScope = properties.filter { query.zoneCode == null || it.property.zoneCode == query.zoneCode }
        val propertyHits = inScope
            .filter {
                matches(it.property.code, query.text) || matches(it.property.name, query.text) ||
                    it.property.companyName?.let { company -> matches(company, query.text) } == true
            }
            .map { it.copy(meters = it.meters.filter(::keep)) }
            .filter { it.meters.isNotEmpty() }
        // Meter-number hits are only listed when the text is not empty and the building did not match already.
        val matchedCodes = propertyHits.map { it.property.code }.toSet()
        val meterHits = if (normalize(query.text).isEmpty()) {
            emptyList()
        } else {
            inScope.flatMap { it.meters }
                .filter { it.propertyCode !in matchedCodes && matches(it.number, query.text) && keep(it) }
        }
        return SearchResults(propertyHits, meterHits)
    }
}
