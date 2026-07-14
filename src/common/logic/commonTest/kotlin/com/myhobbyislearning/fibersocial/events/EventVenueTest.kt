package com.myhobbyislearning.fibersocial.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventVenueTest {

    @Test
    fun `mapsUrl joins all venue parts into an encoded search query`() {
        val venue = EventVenue(
            name = "Chainline Brewing",
            address = "503 6th St S",
            cityState = "Kirkland, Washington",
            country = "United States",
        )
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=" +
                "Chainline%20Brewing%2C%20503%206th%20St%20S%2C%20" +
                "Kirkland%2C%20Washington%2C%20United%20States",
            venue.mapsUrl(),
        )
    }

    @Test
    fun `mapsUrl skips absent and blank parts`() {
        val venue = EventVenue(name = null, address = "  ", cityState = "Kirkland, Washington")
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=Kirkland%2C%20Washington",
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
            "https://www.google.com/maps/search/?api=1&query=Yarn%20%26%20Tea%2C%201%20Main%20St%20%232",
            venue.mapsUrl(),
        )
    }
}
