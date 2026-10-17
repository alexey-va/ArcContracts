import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
    `maven-publish`
}

group = "ru.ruscrafting"
version = providers.gradleProperty("pluginVersion").get()
val fixtureVersion = providers.gradleProperty("fixtureVersion").get()
description = "Standalone contract and season gameplay plugin for RusCrafting"

jacoco {
    toolVersion = "0.8.15"
}

allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

// These CI-only plugins are separate artifacts so the fixture code never enters
// the production shadow JAR. Association grants access to internal child APIs.
val contractTestFixtureSourceSet = sourceSets.create("contractTestFixture") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
kotlin.target.compilations.getByName("contractTestFixture")
    .associateWith(kotlin.target.compilations.getByName("main"))

val contractCrashFixtureSourceSet = sourceSets.create("contractCrashFixture") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
kotlin.target.compilations.getByName("contractCrashFixture")
    .associateWith(kotlin.target.compilations.getByName("main"))

val contractTestFixtureJar = tasks.register<Jar>("contractTestFixtureJar") {
    dependsOn("contractTestFixtureClasses")
    archiveBaseName.set("ArcContractsContractTestFixture")
    archiveVersion.set("")
    archiveClassifier.set("")
    from(contractTestFixtureSourceSet.output)
}

val contractCrashFixtureJar = tasks.register<Jar>("contractCrashFixtureJar") {
    dependsOn("contractCrashFixtureClasses")
    archiveBaseName.set("ArcContractsContractCrashFixture")
    archiveVersion.set("")
    archiveClassifier.set("")
    from(contractCrashFixtureSourceSet.output)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.33")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.33")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.33")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.33")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.33")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.33")

    compileOnly(project(":arc-contracts-api"))
    compileOnly("ru.ruscrafting.arc:arc-core-metrics:2.7.33")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.33")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    compileOnly("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    testImplementation(project(":arc-contracts-api"))
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.33")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.33")
    testRuntimeOnly("ru.ruscrafting.arc:arc-core-metrics:2.7.33")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    testImplementation("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.33")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

    configurations[contractTestFixtureSourceSet.implementationConfigurationName]
        .extendsFrom(configurations["implementation"])
    configurations[contractTestFixtureSourceSet.compileOnlyConfigurationName]
        .extendsFrom(configurations["compileOnly"])
    configurations[contractCrashFixtureSourceSet.implementationConfigurationName]
        .extendsFrom(configurations["implementation"])
    configurations[contractCrashFixtureSourceSet.compileOnlyConfigurationName]
        .extendsFrom(configurations["compileOnly"])
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    forkEvery = 10
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("integrationTest") {
    description = "Runs Redis integration tests against disposable services."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("ArcContracts")
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    mergeServiceFiles()

    // ARC owns these shared service contracts and provider implementations.
    exclude("ru/arc/contracts/api/**")
    exclude("ru/arc/paper/api/**")
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
    exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
    exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("ru/arc/metrics/core/ArcMetricsRuntime*.class")
    exclude("org/bukkit/**")
    exclude("io/papermc/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
    dependsOn(contractTestFixtureJar, contractCrashFixtureJar)
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }
    }
    publications {
        create<MavenPublication>("runtimePlugin") {
            groupId = "ru.ruscrafting"
            artifactId = "ArcContracts"
            version = project.version.toString()
            artifact(tasks.shadowJar)
            pom {
                name.set("ArcContracts")
                description.set("Standalone contract and season gameplay plugin for RusCrafting")
                url.set("https://github.com/alexey-va/ArcContracts")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
        create<MavenPublication>("contractTestFixture") {
            groupId = "ru.ruscrafting"
            artifactId = "ArcContractsContractTestFixture"
            version = fixtureVersion
            artifact(contractTestFixtureJar)
            pom {
                name.set("ArcContracts contract test fixture")
                description.set("CI-only Citizens fixture for ArcContracts Paper integration tests")
                url.set("https://github.com/alexey-va/ArcContracts")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
        create<MavenPublication>("contractCrashFixture") {
            groupId = "ru.ruscrafting"
            artifactId = "ArcContractsContractCrashFixture"
            version = fixtureVersion
            artifact(contractCrashFixtureJar)
            pom {
                name.set("ArcContracts contract crash fixture")
                description.set("CI-only crash-recovery fixture for ArcContracts Paper integration tests")
                url.set("https://github.com/alexey-va/ArcContracts")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
