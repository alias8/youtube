package org.example.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.service.RateLimiter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RateLimitInterceptor(private val rateLimiter: RateLimiter) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // Authenticated requests are keyed by userId; unauthenticated by IP
        val identifier = request.userPrincipal?.name ?: request.remoteAddr
        val key = "rate-limit:reactions:$identifier"

        if (!rateLimiter.isAllowed(key, limit = 20, windowMs = 60_000)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.writer.write("Rate limit exceeded")
            return false
        }
        return true
    }
}
