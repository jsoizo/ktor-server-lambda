package com.jsoizo.ktor.server.lambda.codec

import io.ktor.http.HttpHeaders
import io.ktor.http.IllegalHeaderNameException
import io.ktor.http.IllegalHeaderValueException
import io.ktor.http.URLProtocol

/**
 * Drops headers Ktor would reject, using Ktor's own checks: one bad header must not fail the whole
 * request, and CR/LF in values would allow header injection.
 */
internal fun List<Pair<String, String>>.sanitized(): List<Pair<String, String>> = filter { (name, value) ->
    name.isNotEmpty() && isAccepted { HttpHeaders.checkHeaderName(name) } &&
        isAccepted { HttpHeaders.checkHeaderValue(value) }
}

private inline fun isAccepted(check: () -> Unit): Boolean = try {
    check()
    true
} catch (_: IllegalHeaderNameException) {
    false
} catch (_: IllegalHeaderValueException) {
    false
}

internal fun List<Pair<String, String>>.firstValue(name: String): String? = firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

internal fun List<Pair<String, String>>.lastValue(name: String): String? = lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

internal fun List<Pair<String, String>>.hasHeader(name: String): Boolean = any { it.first.equals(name, ignoreCase = true) }

internal fun List<Pair<String, String>>.partitionSetCookie(): Pair<List<String>, List<Pair<String, String>>> {
    val (cookies, others) = partition { it.first.equals("Set-Cookie", ignoreCase = true) }
    return cookies.map { it.second } to others
}

/** Groups case-insensitively, keeping the first spelling and order of each name. */
internal fun List<Pair<String, String>>.groupByName(): List<Pair<String, List<String>>> {
    val groups = LinkedHashMap<String, Pair<String, MutableList<String>>>()
    for ((name, value) in this) {
        groups.getOrPut(name.lowercase()) { name to mutableListOf() }.second += value
    }
    return groups.values.map { (name, values) -> name to values }
}

// A hop that appends rather than overwrites puts its own value last; earlier values come from the client.
private fun List<Pair<String, String>>.lastForwarded(name: String): String? =
    lastValue(name)?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }

internal fun schemeFrom(headers: List<Pair<String, String>>): String = headers.lastForwarded("X-Forwarded-Proto")?.lowercase() ?: "https"

internal fun portFrom(headers: List<Pair<String, String>>, scheme: String): Int? = headers.lastForwarded("X-Forwarded-Port")?.toIntOrNull()
    ?: when (scheme) {
        URLProtocol.HTTPS.name -> URLProtocol.HTTPS.defaultPort
        URLProtocol.HTTP.name -> URLProtocol.HTTP.defaultPort
        else -> null
    }

/**
 * Splits an `X-Forwarded-For` element into address and port. ALB appends `ip:port` or `[ipv6]:port` when
 * `routing.http.xff_client_port.enabled` is set; otherwise an IPv6 address arrives bare, colons and all.
 */
internal fun splitForwardedFor(element: String): Pair<String, Int?> = when {
    element.startsWith('[') -> element.substring(1).substringBefore(']') to element.substringAfter("]:", "").toIntOrNull()
    element.count { it == ':' } == 1 -> element.substringBefore(':') to element.substringAfter(':').toIntOrNull()
    else -> element to null
}
