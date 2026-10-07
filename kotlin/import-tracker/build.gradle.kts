plugins {
    id("campaign.java-conventions")
    application
}

dependencies {
    implementation(project(":common"))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
}

application {
    mainClass = "dev.mstaszew.campaign.importer.ImportTrackerMainKt"
}
