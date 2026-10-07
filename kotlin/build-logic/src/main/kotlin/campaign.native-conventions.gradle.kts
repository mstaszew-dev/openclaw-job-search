plugins {
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    binaries {
        named("main") {
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

// The community reachability-metadata repository is optional (Spring Boot ships
// its own reachability config) and its zip download breaks offline builds.
tasks.matching { it.name == "collectReachabilityMetadata" }.configureEach {
    enabled = false
}
