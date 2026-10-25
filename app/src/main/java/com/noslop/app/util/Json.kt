package com.noslop.app.util

import com.google.gson.Gson

/**
 * C25: Process-wide shared Gson instance, avoiding repetitive reflection metadata allocations.
 */
object Json {
    val gson: Gson = Gson()
}
