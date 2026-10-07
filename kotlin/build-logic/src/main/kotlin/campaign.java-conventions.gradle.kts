plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxParallelForks = 1
}

// Coordinates mirror gradle/libs.versions.toml (precompiled scripts cannot use
// the type-safe catalog accessors); keep both in sync.
dependencies {
    "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.5.9"))
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("io.mockk:mockk:1.14.11")
    // Gradle 9 test workers require the launcher explicitly
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
