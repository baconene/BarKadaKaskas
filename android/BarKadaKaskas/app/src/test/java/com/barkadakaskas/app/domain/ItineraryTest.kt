package com.barkadakaskas.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ItineraryTest {
    private fun stop(id: Long, start: String, location: String = "", lat: Double? = null, lng: Double? = null, transport: String? = null, min: Long? = null, max: Long? = null) =
        Stop(id, "Stop $id", location, "", start, start, transport, lat, lng, min, max)

    @Test fun groupsStopsByDayInTripTimezone() {
        // 17:30 UTC on the 10th is 01:30 on the 11th in Manila.
        val days = Itinerary.days(listOf(stop(2, "2026-11-11T02:00:00Z"), stop(1, "2026-11-10T17:30:00Z"), stop(3, "2026-11-12T01:00:00Z")), "Asia/Manila")
        assertEquals(listOf(LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12")), days.map { it.key })
        assertEquals(listOf(1L, 2L), days.first().stops.map { it.id })
    }

    @Test fun dayRouteUsesPinsAndSkipsRepeats() {
        val url = Itinerary.dayDirectionsUrl(listOf(
            stop(1, "2026-11-11T00:00:00Z", "Port", 9.2, 123.5), stop(2, "2026-11-11T01:00:00Z", "Port", 9.2, 123.5),
            stop(3, "2026-11-11T02:00:00Z", "Cambugahay Falls"), stop(4, "2026-11-11T03:00:00Z", "Paliton Beach", 9.18, 123.47),
        ))!!
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1&origin=9.2%2C123.5&destination=9.18%2C123.47"))
        assertTrue(url.endsWith("&waypoints=Cambugahay+Falls"))
        assertNull(Itinerary.dayDirectionsUrl(listOf(stop(1, "2026-11-11T00:00:00Z", "Port"))))
    }

    @Test fun travelModeFollowsTransport() {
        val url = Itinerary.directionsUrl(stop(1, "2026-11-11T00:00:00Z", "A"), stop(2, "2026-11-11T01:00:00Z", "B", transport = "walking"))!!
        assertTrue(url.endsWith("travelmode=walking"))
    }

    @Test fun navigationStartsFromCurrentLocation() {
        val url = Itinerary.navigateUrl(stop(1, "2026-11-11T00:00:00Z", "Paliton Beach", 9.18, 123.47, transport = "motorcycle"))!!
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=9.18%2C123.47&travelmode=two-wheeler&dir_action=navigate", url)
        assertTrue(Itinerary.navigateUrl(stop(2, "2026-11-11T00:00:00Z", "Lazi Church"))!!.contains("destination=Lazi+Church"))
        assertNull(Itinerary.navigateUrl(stop(3, "2026-11-11T00:00:00Z")))
    }

    @Test fun phaseCountsDownAndTracksTripDays() {
        val trip = Trip(1, "Siquijor", "Siquijor", "2026-11-11", "2026-11-17")
        assertEquals("In 4 days", Itinerary.phaseLabel(Itinerary.phase(trip, LocalDate.parse("2026-11-07"))))
        assertEquals("Tomorrow!", Itinerary.phaseLabel(Itinerary.phase(trip, LocalDate.parse("2026-11-10"))))
        assertEquals("Day 3 of 7", Itinerary.phaseLabel(Itinerary.phase(trip, LocalDate.parse("2026-11-13"))))
        assertEquals("Completed", Itinerary.phaseLabel(Itinerary.phase(trip, LocalDate.parse("2026-11-18"))))
    }

    @Test fun formatsDateRangesAndCosts() {
        assertEquals("Nov 11 – 17, 2026", Itinerary.dateRange("2026-11-11", "2026-11-17"))
        assertEquals("Nov 28 – Dec 2, 2026", Itinerary.dateRange("2026-11-28", "2026-12-02"))
        assertEquals(28000L to 44000L, Itinerary.totalCost(listOf(stop(1, "x", min = 10000, max = 20000), stop(2, "y", min = 18000, max = 24000), stop(3, "z"))))
        assertNull(Itinerary.costRange(null, 100, "PHP"))
    }

    @Test fun guessesActivityKind() {
        assertEquals(Itinerary.Kind.Food, Itinerary.kind("Lunch at Baha Ba'r"))
        assertEquals(Itinerary.Kind.Water, Itinerary.kind("Cambugahay Falls"))
        assertEquals(Itinerary.Kind.Place, Itinerary.kind("Somewhere"))
    }
}
