package dev.mstaszew.campaign.worker.apply

/**
 * Port of submission_validator.py: negative indicators reject first; otherwise
 * ANY captured confirmation URL or text validates (permissive-any-signal, the
 * same policy the Python validator uses so genuine confirmations in unlisted
 * wording still count as SUBMITTED).
 */
object SubmissionVerifier {

    private val NEGATIVE = listOf(
        "verify your email",
        "verify email",
        "email verification",
        "verification needed",
        "verification required",
        "confirm your email",
        "you have already",
        "already applied",
        "duplicate application",
        "duplicate submission",
        "application failed",
        "submission failed",
        "please try again",
        "nieudane",
        "wystąpił błąd",
    )

    data class Verdict(val valid: Boolean, val reason: String)

    fun evaluate(confirmationUrl: String?, confirmationText: String?): Verdict {
        val url = confirmationUrl?.lowercase().orEmpty()
        val text = confirmationText?.lowercase().orEmpty()
        if (url.isBlank() && text.isBlank()) {
            return Verdict(false, "no confirmation evidence captured")
        }
        NEGATIVE.firstOrNull { url.contains(it) || text.contains(it) }?.let {
            return Verdict(false, "negative indicator: $it")
        }
        return Verdict(true, "evidence captured (${(confirmationText ?: confirmationUrl ?: "").take(60)})")
    }
}
