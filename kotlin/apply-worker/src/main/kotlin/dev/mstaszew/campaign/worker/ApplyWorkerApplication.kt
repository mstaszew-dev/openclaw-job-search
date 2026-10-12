package dev.mstaszew.campaign.worker

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication(scanBasePackages = ["dev.mstaszew.campaign"])
class ApplyWorkerApplication

fun main(args: Array<String>) {
    SpringApplication.run(ApplyWorkerApplication::class.java, *args)
}
