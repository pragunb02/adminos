package dev.adminos.api.infrastructure.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.forwardedheaders.*

/**
 * Trust the **last** `X-Forwarded-For` hop as the client IP. Installed only when `TRUST_FORWARDED_FOR=true`
 * (AOS-618, AUD-03): that is safe only behind an edge proxy that appends the real peer, because the Next proxy
 * itself forwards a client-sent header unchanged. Without it, the TCP peer is the client IP.
 */
fun Application.configureForwardedHeaders() {
    install(XForwardedHeaders) {
        useLastProxy()
    }
}

/** Client IP for rate limiting, audit logs and sessions. Use this, never `request.local.remoteHost`. */
val ApplicationCall.clientIp: String
    get() = request.origin.remoteAddress
