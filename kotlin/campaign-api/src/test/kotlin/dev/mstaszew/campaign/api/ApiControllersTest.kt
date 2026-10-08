package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.api.error.ApiExceptionHandler
import dev.mstaszew.campaign.api.error.NotFoundException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(StatsController::class)
@Import(ApiExceptionHandler::class, StatsControllerTest.Stubs::class)
class StatsControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @TestConfiguration
    class Stubs {
        @Bean
        fun statsService() = StatsService(
            applications = org.mockito.Mockito.mock(dev.mstaszew.campaign.common.repo.ApplicationRepository::class.java).also {
                org.mockito.Mockito.`when`(it.countByStatus(dev.mstaszew.campaign.common.domain.ApplicationStatus.SUBMITTED)).thenReturn(1804L)
            },
            jobState = org.mockito.Mockito.mock(dev.mstaszew.campaign.common.repo.JobStateRepository::class.java).also {
                org.mockito.Mockito.`when`(it.countByStatus(dev.mstaszew.campaign.common.domain.JobStatus.IN_FLIGHT)).thenReturn(2L)
                org.mockito.Mockito.`when`(it.countByStatus(dev.mstaszew.campaign.common.domain.JobStatus.PENDING)).thenReturn(1L)
                org.mockito.Mockito.`when`(it.countByStatus(dev.mstaszew.campaign.common.domain.JobStatus.DEAD)).thenReturn(4L)
            },
            skips = org.mockito.Mockito.mock(dev.mstaszew.campaign.common.repo.SkipRepository::class.java),
            blockers = org.mockito.Mockito.mock(dev.mstaszew.campaign.common.repo.BlockerRepository::class.java),
            lagProbe = org.mockito.Mockito.mock(dev.mstaszew.campaign.common.messaging.ConsumerLagProbe::class.java).also {
                org.mockito.Mockito.`when`(it.lag(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString())).thenReturn(7L)
            },
        )
    }

    @Test
    fun `stats exposes submitted vs target`() {
        mvc.get("/api/v1/stats").andExpect {
            status { isOk() }
            jsonPath("$.submitted") { value(1804) }
            jsonPath("$.target") { value(2000) }
            jsonPath("$.remaining") { value(196) }
            jsonPath("$.inFlight") { value(3) }
            jsonPath("$.dead") { value(4) }
            jsonPath("$.consumerLag") { value(7) }
        }
    }
}

@WebMvcTest(ApplicationController::class)
@Import(ApiExceptionHandler::class, ApplicationControllerTest.Stubs::class)
class ApplicationControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @TestConfiguration
    class Stubs {
        private val app = dev.mstaszew.campaign.common.domain.ApplicationEntity(
            id = "justjoin:9",
            source = "justjoin",
            sourceJobId = "9",
            company = "Acme Ltd",
            companyKey = "acme",
            roleTitle = "Kotlin Developer",
            url = "https://justjoin.it/offers/9",
            status = dev.mstaszew.campaign.common.domain.ApplicationStatus.SUBMITTED,
        )

        @Bean
        fun applicationRepository() = org.mockito.Mockito.mock(dev.mstaszew.campaign.common.repo.ApplicationRepository::class.java).also {
            org.mockito.Mockito.`when`(it.findById("justjoin:9")).thenReturn(java.util.Optional.of(app))
            org.mockito.Mockito.`when`(it.findById("nope")).thenReturn(java.util.Optional.empty())
        }
    }

    @Test
    fun `application by id is mapped without entity leakage`() {
        mvc.get("/api/v1/applications/justjoin:9").andExpect {
            status { isOk() }
            jsonPath("$.id") { value("justjoin:9") }
            jsonPath("$.status") { value("submitted") }
            jsonPath("$.evidence") { doesNotExist() }
        }
    }

    @Test
    fun `missing application is a problem+json 404`() {
        mvc.get("/api/v1/applications/nope").andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Not Found") }
        }
    }
}
