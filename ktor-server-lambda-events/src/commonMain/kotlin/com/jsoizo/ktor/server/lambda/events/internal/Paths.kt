package com.jsoizo.ktor.server.lambda.events.internal

/** Strips only a whole leading segment, so stage `/prod` never truncates `/production`. */
internal fun stripLeadingSegment(path: String, segment: String): String {
    val prefix = "/" + segment.trim('/')
    if (prefix == "/") return path
    return when {
        path == prefix -> "/"
        path.startsWith("$prefix/") -> path.substring(prefix.length)
        else -> path
    }
}

internal fun normalizePath(path: String, stage: String?, applyStage: Boolean, basePath: String?): String {
    var result = path.ifEmpty { "/" }
    if (applyStage && stage != null && stage != "\$default") {
        result = stripLeadingSegment(result, stage)
    }
    if (basePath != null) {
        result = stripLeadingSegment(result, basePath)
    }
    return result.ifEmpty { "/" }
}
