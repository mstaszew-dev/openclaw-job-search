package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.api.error.NotFoundException
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ApplicationDto(
    val id: String,
    val source: String,
    val sourceJobId: String,
    val company: String,
    val companyKey: String,
    val roleTitle: String,
    val url: String?,
    val status: String,
    val appliedAt: Instant?,
    val confirmationUrl: String?,
)

@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController(private val applications: ApplicationRepository) {

    @GetMapping
    fun list(@PageableDefault(size = 50, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable): Page<ApplicationDto> =
        applications.findAll(pageable).map(::toDto)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ApplicationDto =
        applications.findById(id).map(::toDto).orElseThrow { NotFoundException("application $id") }

    private fun toDto(a: dev.mstaszew.campaign.common.domain.ApplicationEntity) = ApplicationDto(
        id = a.id,
        source = a.source,
        sourceJobId = a.sourceJobId,
        company = a.company,
        companyKey = a.companyKey,
        roleTitle = a.roleTitle,
        url = a.url,
        status = a.status.name.lowercase(),
        appliedAt = a.appliedAt,
        confirmationUrl = a.confirmationUrl,
    )
}
