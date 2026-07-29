package com.myhobbyislearning.fibersocial.events

actual fun mapsAppUrl(encodedQuery: String) =
    "https://maps.apple.com/?daddr=$encodedQuery"
