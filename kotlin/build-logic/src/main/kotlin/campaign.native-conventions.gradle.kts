plugins {
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    binaries {
        named("main") {
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // host has 7GB RAM: cap the image-builder JVM heap
            buildArgs.add("-J-Xmx3g")
        }
    }
    // Spring Boot ships its own reachability configs; the community repository's
    // schema can be newer than the pinned GraalVM and fails nativeCompile.
    metadataRepository {
        enabled = false
    }
}

// The community reachability-metadata repository is optional (Spring Boot ships
// its own reachability config) and its zip download breaks offline builds.
tasks.matching { it.name == "collectReachabilityMetadata" }.configureEach {
    enabled = false
}
