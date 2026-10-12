plugins {
    id("campaign.java-conventions")
    application
}

dependencies {
    implementation(project(":common"))
    testImplementation(testFixtures(project(":common")))
    implementation(libs.jackson.module.kotlin)
    // The importer talks to Mongo with the driver directly, not through Spring.
    implementation(libs.mongodb.driver.sync)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit.jupiter)
}

application {
    mainClass = "dev.mstaszew.campaign.importer.ImportTrackerMainKt"
}
