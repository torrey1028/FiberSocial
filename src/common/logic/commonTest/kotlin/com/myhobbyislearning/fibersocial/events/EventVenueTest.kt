package com.myhobbyislearning.fibersocial.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventVenueTest {

    // mapsUrl()'s query-joining/encoding is the platform-independent part this test
    // suite actually targets; the URL template itself is per-platform (issue #328 —
    // google.com/maps/dir on Android/jvm, maps.apple.com on iOS). Building the expected
    // value through mapsAppUrl() rather than hardcoding one platform's literal keeps
    // these assertions correct under every target's actual, including
    // iosSimulatorArm64Test — hardcoding the Android/jvm literal here previously broke
    // silently on iOS (mapsUrl() legitimately returns a different, still-correct, URL
    // there).

    @Test
    fun `mapsUrl joins all venue parts into an encoded directions destination`() {
        val venue = EventVenue(
            name = "Chainline Brewing",
            address = "503 6th St S",
            cityState = "Kirkland, Washington",
            country = "United States",
        )
        assertEquals(
            mapsAppUrl("Chainline%20Brewing%2C%20503%206th%20St%20S%2C%20Kirkland%2C%20Washington%2C%20United%20States"),
            venue.mapsUrl(),
        )
    }

    @Test
    fun `mapsUrl skips absent and blank parts`() {
        val venue = EventVenue(name = null, address = "  ", cityState = "Kirkland, Washington")
        assertEquals(
            mapsAppUrl("Kirkland%2C%20Washington"),
            venue.mapsUrl(),
        )
    }

    @Test
    fun `mapsUrl is null when the venue has no usable parts`() {
        assertNull(EventVenue().mapsUrl())
        assertNull(EventVenue(name = " ", address = "").mapsUrl())
    }

    @Test
    fun `mapsUrl encodes characters that would break the query`() {
        val venue = EventVenue(name = "Yarn & Tea", address = "1 Main St #2")
        assertEquals(
            mapsAppUrl("Yarn%20%26%20Tea%2C%201%20Main%20St%20%232"),
            venue.mapsUrl(),
        )
    }
}
