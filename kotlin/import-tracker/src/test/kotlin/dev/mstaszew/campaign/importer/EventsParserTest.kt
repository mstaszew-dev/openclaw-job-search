package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventsParserTest {

    @Test
    fun `parses jsonl lines`() {
        val text = javaClass.classLoader.getResource("fixture-events.jsonl")!!.readText()

        val events = EventsParser(ObjectMapper()).parse(text)

        assertThat(events).hasSize(2)
        assertThat(events[0].action).isEqualTo("submitted")
        assertThat(events[0].record.toString()).contains("nofluffjobs:aaa-1")
        assertThat(events[1].action).isEqualTo("skippedDuplicate")
    }

    @Test
    fun `skips blank lines`() {
        val events = EventsParser(ObjectMapper()).parse("\n{\"at\":\"2026-01-01T00:00:00Z\",\"action\":\"submitted\",\"record\":{}}\n\n")
        assertThat(events).hasSize(1)
    }
}
