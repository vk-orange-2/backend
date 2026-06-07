plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // JMeter Java DSL - use latest version
    implementation("us.abstracta.jmeter:jmeter-java-dsl:1.27")
    
    // SLF4J for logging
    implementation("org.slf4j:slf4j-simple:2.0.9")
    
    // JUnit 5 for tests
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

configurations.all {
    resolutionStrategy.eachDependency {
        // Force JMeter version 5.6.3 for all JMeter dependencies
        if (requested.group == "org.apache.jmeter") {
            useVersion("5.6.3")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass.set("ru.itmo.config_streamer.loadtests.ConfigServerLoadTest")
}

// Pass system properties to the application
tasks.named<JavaExec>("run") {
    System.getProperties().forEach { key, value ->
        if (key is String && (key.startsWith("config.") || key.startsWith("test."))) {
            systemProperty(key, value)
        }
    }
}

group = "ru.itmo.config_streamer"
version = "0.0.1-SNAPSHOT"
