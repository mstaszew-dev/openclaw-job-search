package dev.mstaszew.campaign.worker

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["dev.mstaszew.campaign"])
@EntityScan("dev.mstaszew.campaign")
@EnableJpaRepositories("dev.mstaszew.campaign")
class ApplyWorkerApplication

fun main(args: Array<String>) {
    SpringApplication.run(ApplyWorkerApplication::class.java, *args)
}
