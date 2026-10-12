package dev.mstaszew.campaign.finder

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication(scanBasePackages = ["dev.mstaszew.campaign"])
class FinderServiceApplication

fun main(args: Array<String>) {
    SpringApplication.run(FinderServiceApplication::class.java, *args)
}
