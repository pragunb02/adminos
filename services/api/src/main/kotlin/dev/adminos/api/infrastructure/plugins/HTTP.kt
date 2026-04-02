package dev.adminos.api.infrastructure.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureHTTP(appUrl: String) {
    install(CORS) {
        allowHost(appUrl.removePrefix("http://").removePrefix("https://"))
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowCredentials = true
    }
}
