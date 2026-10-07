plugins {
    id("campaign.java-conventions")
    id("org.springframework.boot")
    id("org.jetbrains.kotlin.plugin.spring")
}

// Coordinates mirror gradle/libs.versions.toml; keep both in sync.
dependencies {
    "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.5.9"))
    "implementation"("org.springframework.boot:spring-boot-starter-web")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.flywaydb:flyway-core")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
    "runtimeOnly"("org.postgresql:postgresql")

    "testImplementation"(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    "testImplementation"("org.testcontainers:postgresql")
    "testImplementation"("org.testcontainers:junit-jupiter")
}

// Test AOT boots @SpringBootTest contexts during the build (and would start
// Testcontainers even without Docker); only main AOT is needed for native images.
tasks.matching { it.name == "processTestAot" }.configureEach {
    enabled = false
}
