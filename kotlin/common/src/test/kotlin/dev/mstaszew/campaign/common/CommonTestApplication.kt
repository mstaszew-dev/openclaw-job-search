package dev.mstaszew.campaign.common

import org.springframework.boot.SpringBootConfiguration

/**
 * Anchor for the slice tests in this module.
 *
 * `common` is a library, not an application, so it has no
 * `@SpringBootConfiguration` of its own and `@DataMongoTest` cannot find one by
 * searching upwards from `IndexBootstrapIT`. Declaring it here is what lets the
 * Mongo slice tests in `common` boot; the other three modules have real
 * application classes and do not need this.
 *
 * Deliberately `@SpringBootConfiguration` and NOT `@SpringBootApplication`:
 * the latter component-scans `dev.mstaszew.campaign.common`, which would drag
 * `KafkaWiring` and its unsatisfied `spring.kafka.bootstrap-servers` placeholder
 * into every Mongo slice. The slice supplies the auto-configuration and the
 * repositories itself.
 *
 * Test-only on purpose: it must never end up on a production classpath. Gradle's
 * test-fixtures plugin exports only `src/testFixtures`, so no other module sees
 * this class.
 */
@SpringBootConfiguration
class CommonTestApplication