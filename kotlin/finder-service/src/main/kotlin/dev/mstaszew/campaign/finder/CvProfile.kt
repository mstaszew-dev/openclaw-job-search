package dev.mstaszew.campaign.finder

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * CV profile text handed to the LLM scorer. Read from a mounted file so no PII
 * lives in the repo or the image (the k8s Deployment mounts the applicant
 * profile; see deploy/ docs).
 */
@Component
class CvProfile(
    @Value("\${finder.cv-profile-path:}") private val configuredPath: String,
) {

    private val cached: String by lazy {
        val path = configuredPath.trim()
        when {
            path.isEmpty() -> DEFAULT_PROFILE
            Files.isReadable(Path.of(path)) -> Files.readString(Path.of(path))
            else -> {
                // Deliberately degrade instead of crashing the finder: scoring
                // falls back and the run is still useful.
                DEFAULT_PROFILE
            }
        }
    }

    fun profileText(): String = cached

    companion object {
        private val DEFAULT_PROFILE = """
            Senior backend engineer. Core: Java, Kotlin, Spring Boot (REST APIs,
            queues, microservices). Secondary: PHP/Laravel, Node.js, React,
            TypeScript. Strong TDD, code review and CI/CD habits. All seniority
            levels accepted; no team-lead/management ambitions.
        """.trimIndent()
    }
}
