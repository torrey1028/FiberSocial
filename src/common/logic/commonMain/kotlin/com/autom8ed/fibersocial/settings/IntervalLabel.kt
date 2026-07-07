package com.autom8ed.fibersocial.settings

/** Human label for a poll interval choice. */
fun intervalLabel(hours: Int): String =
    if (hours == 1) "Every hour" else "Every $hours hours"
