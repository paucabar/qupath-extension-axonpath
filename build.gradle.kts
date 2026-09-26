plugins {
    id("qupath-conventions")
    `maven-publish`
}

qupathExtension {
    name = "qupath-extension-axonpath"
    version = "0.1.0-SNAPSHOT"
    group = "io.github.paucabar"
    description = "Run AxonPath deep learning models to segment fibres, inner cylinders and axons in EM and brightfield images, with morphometric measurements and tools for manual correction"
    automaticModule = "qupath.extension.axonpath"
}

dependencies {

    implementation(libs.bundles.qupath)
    implementation(libs.bundles.logging)
    implementation(libs.qupath.fxtras)

    implementation(libs.snakeyaml)
    implementation(libs.deepJavaLibrary)
    implementation(libs.qupath.djl)

    // For testing
    testImplementation(libs.junit)
    // Gradle 9 no longer puts the JUnit Platform launcher on the test classpath automatically
    testRuntimeOnly(libs.junit.platform)

}

publishing {
    repositories {
        maven {
            name = "SciJava"
            val releasesRepoUrl = uri("https://maven.scijava.org/content/repositories/releases")
            val snapshotsRepoUrl = uri("https://maven.scijava.org/content/repositories/snapshots")
            // Use gradle -Prelease publish
            url = if (project.hasProperty("release")) releasesRepoUrl else snapshotsRepoUrl
            credentials {
                username = System.getenv("MAVEN_USER")
                password = System.getenv("MAVEN_PASS")
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                licenses {
                    license {
                        name = "Apache License v2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}
