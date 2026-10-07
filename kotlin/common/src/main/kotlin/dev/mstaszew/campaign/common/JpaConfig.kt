package dev.mstaszew.campaign.common

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * JPA wiring for every service (picked up via component scan). Kept off the
 * application classes so @WebMvcTest slices do not drag in repositories and
 * demand a DataSource.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("dev.mstaszew.campaign")
@EnableJpaRepositories("dev.mstaszew.campaign")
class JpaConfig
