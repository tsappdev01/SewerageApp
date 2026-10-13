package com.meterreading.reader.data

/**
 * The meters of one property the reader can switch between while reading it (Sewerage, Irrigation):
 * the one open now and the others still to read. Sewerage first, then by route. One meter: no switch.
 */
object MeterSwitch {
    fun options(meters: List<Meter>, current: Meter): List<Meter> {
        val others = meters.filter { it.propertyCode == current.propertyCode && it.id != current.id && it.state.canCapture }
        return (others + current).sortedWith(compareBy<Meter>({ it.type != MeterType.SEWERAGE }, { it.route }, { it.number }))
    }
}
