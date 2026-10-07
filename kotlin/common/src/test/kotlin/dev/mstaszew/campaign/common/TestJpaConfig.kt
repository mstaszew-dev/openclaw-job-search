package dev.mstaszew.campaign.common

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/** Minimal context for @DataJpaTest slices in the common module (no app class here). */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("dev.mstaszew.campaign.common.domain")
@EnableJpaRepositories("dev.mstaszew.campaign.common.repo")
class TestJpaConfig
