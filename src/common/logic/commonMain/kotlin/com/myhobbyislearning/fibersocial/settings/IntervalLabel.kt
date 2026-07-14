package com.myhobbyislearning.fibersocial.settings

/** Human label for a poll interval choice. */
fun intervalLabel(hours: Int): String =
    if (hours == 1) "Every hour" else "Every $hours hours"
