package dev.mstaszew.campaign.api.error

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class NotFoundException(what: String) : RuntimeException("$what not found")

class ConflictException(message: String) : RuntimeException(message)

/** Stable error envelope: RFC 7807 problem+json for every failure mode. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = problem(HttpStatus.NOT_FOUND, e.message ?: "not found")

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException) = problem(HttpStatus.CONFLICT, e.message ?: "conflict")

    /**
     * Spring JDBC's DataIntegrityViolationException went away with Postgres;
     * Mongo raises DuplicateKeyException for a unique-index collision, which
     * is the same 409 case (an application already exists for this job id).
     */
    @ExceptionHandler(DuplicateKeyException::class)
    fun dataConflict(e: DuplicateKeyException) =
        problem(HttpStatus.CONFLICT, "conflicting state (constraint violation)")

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = problem(HttpStatus.BAD_REQUEST, e.message ?: "bad request")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java)
            .error("unhandled API error", e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error")
    }

    private fun problem(status: HttpStatus, detail: String): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(status, detail)
        pd.title = status.reasonPhrase
        return pd
    }
}
