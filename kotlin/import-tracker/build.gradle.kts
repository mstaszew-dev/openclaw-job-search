plugins {
    id("campaign.java-conventions")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.postgresql)
}

application {
    mainClass = "dev.mstaszew.campaign.importer.ImportTrackerMainKt"
}
