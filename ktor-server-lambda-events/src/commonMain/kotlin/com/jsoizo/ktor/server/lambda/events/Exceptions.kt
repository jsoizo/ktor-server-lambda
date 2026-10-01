package com.jsoizo.ktor.server.lambda.events

/** The event has a format this library does not handle, such as Lambda@Edge or a non-HTTP event. */
public class UnsupportedEventException(
    /** Human-readable name of the format, such as `Lambda@Edge`. */
    public val kind: String,
) : RuntimeException("Unsupported event: $kind")

/** The format was detected, but a required field is missing or has the wrong type. */
public class InvalidEventException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
