package com.myhobbyislearning.fibersocial.events

// The jvm() target isn't shipped (see build.gradle.kts) — this actual exists solely so
// EventVenueTest can compile/run under jvmTest for coverage. Mirrors the Android actual.
actual fun mapsAppUrl(encodedQuery: String) =
    "https://www.google.com/maps/dir/?api=1&destination=$encodedQuery"
