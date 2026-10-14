pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.citizensnpcs.co/repo") {
            content { includeGroup("net.citizensnpcs") }
        }
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MilkBowl") }
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            content { includeGroup("io.papermc.paper") }
        }
        maven("https://repo.lucko.me/") {
            content { includeGroup("net.luckperms") }
        }
        maven("https://repo.codemc.io/repository/maven-public/") {
            content { includeGroup("com.github.retrooper") }
        }
        maven("https://repo.rus-crafting.ru/grocermc/") {
            content {
                includeGroup("ru.ruscrafting.arc")
                includeGroup("ru.ruscrafting.thirdparty")
            }
        }
    }
}

rootProject.name = "ArcContracts"
include("arc-contracts-api")

providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { arcCoreDir ->
    require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
        "arcCoreDir must point to an arc-core checkout"
    }
    includeBuild(arcCoreDir) {
        dependencySubstitution {
            listOf(
                "arc-core",
                "arc-core-integration-testing",
                "arc-core-logging",
                "arc-core-menu",
                "arc-core-metrics",
                "arc-core-paper",
                "arc-core-paper-api",
                "arc-core-paper-menu",
                "arc-core-paper-testing",
                "arc-core-redis",
            ).forEach { artifact ->
                substitute(module("ru.ruscrafting.arc:$artifact")).using(project(":$artifact"))
            }
        }
    }
}
