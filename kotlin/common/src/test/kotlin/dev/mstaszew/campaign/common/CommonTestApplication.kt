package dev.mstaszew.campaign.common

import org.springframework.boot.SpringBootConfiguration
import org.springframework.context.annotation.Import

/**
 * Anchor for the slice tests in this module.
 *
 * `common` is a library, not an application, so it has no
 * `@SpringBootConfiguration` of its own and `@DataMongoTest` cannot find one by
 * searching upwards from `IndexBootstrapIT`. The other three modules have real
 * application classes and do not need this.
 *
 * Two deliberate departures from @SpringBootApplication:
 *
 * - No component scan. @DataMongoTest filters scanned components to Mongo ones
 *   only, and a scan of this package would still reach KafkaWiring and its
 *   unsatisfied `spring.kafka.bootstrap-servers` placeholder.
 * - No @EnableAutoConfiguration. @DataMongoTest is @OverrideAutoConfiguration
 *   (enabled = false), so Spring strips that annotation off the anchor anyway.
 *   Without it, @DataMongoTest's own repository auto-configuration cannot
 *   resolve its base packages and fails with "Unable to retrieve
 *   @EnableAutoConfiguration base packages".
 *
 * MongoConfig is imported instead, which registers the repositories with the
 * base packages spelled out, the transaction manager, and the JsonNode
 * converters. It is the same class the services pick up by scan.
 *
 * Test-only on purpose: it must never end up on a production classpath. Gradle's
 * test-fixtures plugin exports only `src/testFixtures`, so no other module sees
 * this class.
 */
@SpringBootConfiguration
@Import(MongoConfig::class)
class CommonTestApplication