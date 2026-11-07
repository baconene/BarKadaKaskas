package com.barkadakaskas.app.domain

import java.math.BigDecimal
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** Pure itinerary helpers (no Android types) so they can be unit tested on the JVM. Mirrors resources/js/lib/itinerary.ts. */
object Itinerary {
    data class Day(val key: LocalDate, val stops: List<Stop>)

    fun ordered(stops: List<Stop>): List<Stop> = stops.sortedWith(compareBy({ instant(it.startsAt) }, { it.id }))

    /** Stops grouped by calendar day in the trip's timezone, in order. */
    fun days(stops: List<Stop>, timezone: String): List<Day> = ordered(stops)
        .groupBy { dayOf(it.startsAt, timezone) }
        .map { (key, items) -> Day(key, items) }
        .sortedBy { it.key }

    fun dayOf(value: String, timezone: String): LocalDate = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone(timezone)).toLocalDate() }
        .getOrElse { runCatching { LocalDate.parse(value.take(10)) }.getOrElse { LocalDate.MIN } }

    fun dayLabel(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
    fun clock(value: String, timezone: String): String = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone(timezone)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) }.getOrElse { "" }

    /** "Nov 11 – 17, 2026", "Nov 28 – Dec 2, 2026" or "Dec 30, 2026 – Jan 2, 2027". */
    fun dateRange(start: String, end: String): String {
        val from = runCatching { LocalDate.parse(start.take(10)) }.getOrNull() ?: return "$start – $end"
        val to = runCatching { LocalDate.parse(end.take(10)) }.getOrNull() ?: return "$start – $end"
        val md = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        return when {
            from == to -> "${from.format(md)}, ${from.year}"
            from.year != to.year -> "${from.format(md)}, ${from.year} – ${to.format(md)}, ${to.year}"
            from.month == to.month -> "${from.format(md)} – ${to.dayOfMonth}, ${to.year}"
            else -> "${from.format(md)} – ${to.format(md)}, ${to.year}"
        }
    }

    sealed interface Phase { data class Upcoming(val days: Long) : Phase; data class Ongoing(val day: Long, val of: Long) : Phase; data object Past : Phase }

    fun phase(trip: Trip, today: LocalDate): Phase {
        val start = runCatching { LocalDate.parse(trip.startsOn) }.getOrNull() ?: return Phase.Upcoming(0)
        val end = runCatching { LocalDate.parse(trip.endsOn) }.getOrNull() ?: start
        return when {
            today.isBefore(start) -> Phase.Upcoming(ChronoUnit.DAYS.between(today, start))
            today.isAfter(end) -> Phase.Past
            else -> Phase.Ongoing(ChronoUnit.DAYS.between(start, today) + 1, ChronoUnit.DAYS.between(start, end) + 1)
        }
    }

    fun phaseLabel(phase: Phase): String = when (phase) {
        is Phase.Upcoming -> when (phase.days) { 0L -> "Starts today"; 1L -> "Tomorrow!"; else -> "In ${phase.days} days" }
        is Phase.Ongoing -> "Day ${phase.day} of ${phase.of}"
        Phase.Past -> "Completed"
    }

    fun hasPin(stop: Stop) = stop.latitude != null && stop.longitude != null

    /** True when two consecutive stops are effectively the same place (no travel needed). */
    fun samePlace(a: Stop, b: Stop): Boolean {
        if (hasPin(a) && hasPin(b)) return abs(a.latitude!! - b.latitude!!) < 0.00045 && abs(a.longitude!! - b.longitude!!) < 0.00045
        return a.location.isNotBlank() && a.location.trim().equals(b.location.trim(), ignoreCase = true)
    }

    private fun point(stop: Stop): String? = when {
        stop.location.isBlank() -> null
        hasPin(stop) -> "${stop.latitude},${stop.longitude}"
        else -> stop.location.trim()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    fun searchUrl(stop: Stop): String = "https://www.google.com/maps/search/?api=1&query=" + encode(point(stop) ?: stop.title)

    fun travelMode(transport: String?): String = when (transport) {
        "walking" -> "walking"; "bicycle" -> "bicycling"; "transit", "ferry" -> "transit"; "motorcycle" -> "two-wheeler"; else -> "driving"
    }

    fun directionsUrl(from: Stop, to: Stop): String? {
        val origin = point(from) ?: return null
        val destination = point(to) ?: return null
        return "https://www.google.com/maps/dir/?api=1&origin=${encode(origin)}&destination=${encode(destination)}&travelmode=${travelMode(to.transport)}"
    }

    /** Google Maps directions through a day's stops in order (consecutive duplicates removed, at most 9 waypoints). */
    fun dayDirectionsUrl(stops: List<Stop>): String? {
        val points = mutableListOf<String>()
        stops.forEach { stop -> point(stop)?.let { if (points.lastOrNull() != it) points += it } }
        if (points.size < 2) return null
        val waypoints = points.subList(1, points.size - 1).take(9)
        val base = "https://www.google.com/maps/dir/?api=1&origin=${encode(points.first())}&destination=${encode(points.last())}&travelmode=driving"
        return if (waypoints.isEmpty()) base else base + "&waypoints=" + encode(waypoints.joinToString("|"))
    }

    /** Per-person estimate, e.g. "₱280–440" / "₱300", or null when there is none. */
    fun costRange(min: Long?, max: Long?, currency: String): String? {
        if (min == null || max == null) return null
        return if (min == max) wholeMoney(min, currency) else "${wholeMoney(min, currency)}–${wholeMoney(max, currency).filter { it.isDigit() || it == ',' || it == '.' }}"
    }

    fun totalCost(stops: List<Stop>): Pair<Long, Long>? {
        val priced = stops.filter { it.costMinMinor != null && it.costMaxMinor != null }
        if (priced.isEmpty()) return null
        return priced.sumOf { it.costMinMinor!! } to priced.sumOf { it.costMaxMinor!! }
    }

    fun wholeMoney(minor: Long, currency: String): String = NumberFormat.getCurrencyInstance(Locale("en", "PH")).apply {
        this.currency = runCatching { Currency.getInstance(currency) }.getOrDefault(Currency.getInstance("PHP"))
        maximumFractionDigits = if (minor % 100 == 0L) 0 else 2
        minimumFractionDigits = if (minor % 100 == 0L) 0 else 2
    }.format(BigDecimal.valueOf(minor, 2))

    enum class Kind { Food, Flight, Ferry, Stay, Water, Sight, Shop, Place }
    private val kindRules = listOf(
        Kind.Flight to Regex("\\b(flight|airport|depart manila|arrive in manila)\\b", RegexOption.IGNORE_CASE),
        Kind.Ferry to Regex("\\b(ferry|port|boat|pier)\\b", RegexOption.IGNORE_CASE),
        Kind.Food to Regex("\\b(breakfast|lunch|dinner|brunch|cafe|café|coffee|restaurant|food|eat|snack)\\b", RegexOption.IGNORE_CASE),
        Kind.Stay to Regex("\\b(check in|check-in|check out|hotel|resort|night|rest|relax|cove|inn|hostel)\\b", RegexOption.IGNORE_CASE),
        Kind.Water to Regex("\\b(beach|snorkel|swim|dive|falls|spring|sunset|island)\\b", RegexOption.IGNORE_CASE),
        Kind.Shop to Regex("\\b(shop|souvenir|market|mall)\\b", RegexOption.IGNORE_CASE),
        Kind.Sight to Regex("\\b(church|cave|tour|museum|viewpoint|tree|park|sightseeing|hike|boulevard)\\b", RegexOption.IGNORE_CASE),
    )
    fun kind(title: String): Kind = kindRules.firstOrNull { it.second.containsMatchIn(title) }?.first ?: Kind.Place

    private fun zone(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Manila"))
    private fun instant(value: String) = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: java.time.Instant.MAX
}
