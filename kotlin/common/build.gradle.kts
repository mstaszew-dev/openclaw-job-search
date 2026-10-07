plugins {
    id("campaign.java-conventions")
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.data.jpa)
    api(libs.jackson.module.kotlin)

    // common carries the Flyway migrations and runs them in its own test slices
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
