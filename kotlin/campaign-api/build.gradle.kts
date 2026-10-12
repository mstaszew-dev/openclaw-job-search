plugins {
    id("campaign.spring-conventions")
    id("campaign.native-conventions")
}

dependencies {
    implementation(project(":common"))
    testImplementation(testFixtures(project(":common")))
}
