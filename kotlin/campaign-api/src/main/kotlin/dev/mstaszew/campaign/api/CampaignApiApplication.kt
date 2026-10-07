package dev.mstaszew.campaign.api

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication(scanBasePackages = ["dev.mstaszew.campaign"])
class CampaignApiApplication

fun main(args: Array<String>) {
    SpringApplication.run(CampaignApiApplication::class.java, *args)
}
