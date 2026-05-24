plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("io.github.centrifugal:centrifuge-java:0.5.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    
    // Micrometer for metrics (integrates with Spring Boot Actuator)
    implementation("io.micrometer:micrometer-core:1.15.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

group = "ru.itmo.config_streamer"
version = "0.0.1-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "ru.itmo.config_streamer"
            artifactId = "sdk"
            version = "1.0"

            from(components["java"])
        }
    }
}
