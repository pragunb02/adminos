package dev.adminos.api.infrastructure.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.forwardedheaders.*

/**
 * The browser reaches the API through the web app's same-origin proxy (D-32), so the TCP peer is
 * the proxy. The proxy appends the real client to `X-Forwarded-For`. We trust only the **last**
 * hop (the one our proxy added), so a client can't spoof its IP by sending its own header.
 */
fun Application.configureForwardedHeaders() {
    install(XForwardedHeaders) {
        useLastProxy()
    }
}

/** Client IP for rate limiting, audit logs and sessions. Use this, never `request.local.remoteHost`. */
val ApplicationCall.clientIp: String
    get() = request.origin.remoteAddress
