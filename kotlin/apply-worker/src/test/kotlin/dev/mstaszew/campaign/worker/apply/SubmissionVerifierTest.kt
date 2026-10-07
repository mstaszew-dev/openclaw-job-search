package dev.mstaszew.campaign.worker.apply

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubmissionVerifierTest {

    @Test
    fun `no evidence never counts`() {
        assertThat(SubmissionVerifier.evaluate(null, null).valid).isFalse()
        assertThat(SubmissionVerifier.evaluate("", "").valid).isFalse()
    }

    @Test
    fun `negative indicators win`() {
        assertThat(SubmissionVerifier.evaluate(null, "you have already applied for this position").valid).isFalse()
        assertThat(SubmissionVerifier.evaluate(null, "please verify your email").valid).isFalse()
        assertThat(SubmissionVerifier.evaluate(null, "Wystąpił błąd").valid).isFalse()
    }

    @Test
    fun `positive text or url counts`() {
        assertThat(SubmissionVerifier.evaluate(null, "Thank you for applying!").valid).isTrue()
        assertThat(SubmissionVerifier.evaluate("https://ats.example.com/success", null).valid).isTrue()
        assertThat(SubmissionVerifier.evaluate("https://ats.example.com/applied/123", null).valid).isTrue()
        assertThat(SubmissionVerifier.evaluate(null, "Twoja aplikacja została wysłana pomyślnie").valid).isTrue()
    }

    @Test
    fun `any captured evidence counts unless a negative indicator matches (permissive port)`() {
        assertThat(SubmissionVerifier.evaluate(null, "form submitted ok-ish").valid).isTrue()
    }
}
