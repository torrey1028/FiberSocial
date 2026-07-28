package com.myhobbyislearning.fibersocial.events

actual fun mapsAppUrl(encodedQuery: String) =
    "https://www.google.com/maps/dir/?api=1&destination=$encodedQuery"
