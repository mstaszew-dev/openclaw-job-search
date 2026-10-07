package dev.mstaszew.campaign.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StatsController(private val statsService: StatsService) {

    @GetMapping("/api/v1/stats")
    fun stats(): CampaignStats = statsService.stats()
}
