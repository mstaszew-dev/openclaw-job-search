plugins {
    id("campaign.java-conventions")
    // Shared container harnesses live here rather than in src/test so the
    // service and importer modules can reuse the same Mongo replica set and
    // Kafka broker instead of each standing up its own.
    `java-test-fixtures`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.data.mongodb)
    // The messaging package (JobMessage, Topics, KafkaWiring, ConsumerLagProbe)
    // lives in common so the finder, the worker and the API all share one wire
    // format and one set of consumer settings.
    api(libs.spring.kafka)
    api(libs.jackson.module.kotlin)

    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi(libs.testcontainers.core)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.mongodb.driver.sync)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    // @Testcontainers(disabledWithoutDocker = true) lives here, and it is what
    // lets these integration tests self-skip on a machine with no Docker
    // instead of failing the local build.
    testImplementation(libs.testcontainers.junit.jupiter)
}
