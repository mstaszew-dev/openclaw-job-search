plugins {
    `kotlin-dsl`
}

// Plugin versions mirror gradle/libs.versions.toml; keep both in sync.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.4.20")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:3.5.9")
    implementation("org.graalvm.buildtools:native-gradle-plugin:1.1.14")
}
