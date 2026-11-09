package com.barkadakaskas.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppNavigationTest {
    @Test fun transportMapsToNavigationSdkModes() {
        assertEquals(InAppNavigation.Mode.DRIVING, InAppNavigation.mode(null))
        assertEquals(InAppNavigation.Mode.DRIVING, InAppNavigation.mode("taxi"))
        assertEquals(InAppNavigation.Mode.TWO_WHEELER, InAppNavigation.mode("tricycle"))
        assertEquals(InAppNavigation.Mode.CYCLING, InAppNavigation.mode("bicycle"))
        assertEquals(InAppNavigation.Mode.WALKING, InAppNavigation.mode("walking"))
        // Only Google Maps routes these.
        assertNull(InAppNavigation.mode("ferry")); assertNull(InAppNavigation.mode("transit")); assertNull(InAppNavigation.mode("flight"))
    }

    @Test fun dayStopsRoundTripToTheNavigationScreen() {
        val stops = listOf(
            InAppNavigation.NavStop("Siquijor Port", "Siquijor Port, Siquijor", 9.21, 123.51, null, "3:00 PM", "https://maps/1", done = true),
            InAppNavigation.NavStop("Isla Cove", null, null, null, "tricycle", "4:00 PM", "https://maps/2"),
        )
        assertEquals(stops, InAppNavigation.decode(InAppNavigation.encode(stops)))
        assertEquals(emptyList<InAppNavigation.NavStop>(), InAppNavigation.decode("not json"))
    }
}
