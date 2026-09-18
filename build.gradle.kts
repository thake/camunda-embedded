plugins {
    kotlin("jvm") version "2.4.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val camundaDist by configurations.creating

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.camunda:camunda-process-test-java:8.9.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.2")
    camundaDist("io.camunda:camunda-zeebe:8.9.21@tar.gz")
}

val unpackCamunda by tasks.registering(Sync::class) {
    from(provider { tarTree(camundaDist.singleFile) })
    into(layout.buildDirectory.dir("camunda-dist"))
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(unpackCamunda)
    systemProperty("camunda.dist.path", layout.buildDirectory.dir("camunda-dist").get().asFile.absolutePath)
}