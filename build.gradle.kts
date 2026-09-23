plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm") version "2.4.0"
}

group = "org.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        force("org.agrona:agrona:1.23.1")
    }
}

dependencies {
    // Public APIs transitively exposed to consumers:
    api("io.camunda:camunda-process-test-java:8.9.21")
    api("org.junit.jupiter:junit-jupiter-api:5.11.4")

    // Internal utilities:
    implementation("org.awaitility:awaitility-kotlin:4.2.2")

    // Camunda Server Runtime dependencies (transitively resolved for consumers):
    api("org.camunda.bpm:camunda-license-check:2.11.2")
    api("org.agrona:agrona:1.23.1")
    api("io.camunda:camunda-zeebe:8.9.21") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
        exclude(group = "ch.qos.logback")
        exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
    }

    // Dependencies for testing the library itself:
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "org.example"
            artifactId = "camunda-embedded-test"
            version = "1.0.0"
        }
    }
}