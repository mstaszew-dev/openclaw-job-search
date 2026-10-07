package dev.mstaszew.campaign.finder

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["dev.mstaszew.campaign"])
@EntityScan("dev.mstaszew.campaign")
@EnableJpaRepositories("dev.mstaszew.campaign")
class FinderServiceApplication

fun main(args: Array<String>) {
    SpringApplication.run(FinderServiceApplication::class.java, *args)
}
