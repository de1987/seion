pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Security floors for transitive dependencies that our plugins and tools (AGP, Android Lint, ktlint, KSP) and the
// app pull in at versions with published advisories. A floor only ever raises a requested version, never lowers
// one, and applies to every project's buildscript classpath as well as to all of its configurations. Drop an entry
// once nothing requests a version below it any more.
val securityFloors =
    mapOf(
        "com.google.guava:guava" to "33.7.2", // GHSA-xxph-c9ww-hj94; keeps the -android / -jre flavour
        "org.bouncycastle:*-jdk18on" to "1.85", // GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-wg6q-6289-32hp
        "ch.qos.logback:logback-core" to "1.5.38", // GHSA-25qh-j22f-pwp8 and five more; 1.3.x has no fix
        "ch.qos.logback:logback-classic" to "1.5.38", // moves with logback-core
        "org.bitbucket.b_c:jose4j" to "0.9.6", // GHSA-3677-xxcr-wjqv
        "org.jdom:jdom2" to "2.0.6.1", // GHSA-2363-cqg2-863c
        "org.apache.commons:commons-lang3" to "3.18.0", // GHSA-j288-q9x7-2f5v
        "org.apache.httpcomponents:httpclient" to "4.5.14", // GHSA-7r82-7xv7-xcpj
    )

fun numericParts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

fun isBelow(
    version: String,
    floor: String,
): Boolean {
    val a = numericParts(version)
    val b = numericParts(floor)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

fun floorFor(
    group: String,
    name: String,
): String? =
    securityFloors["$group:$name"]
        ?: securityFloors.entries
            .firstOrNull { (key, _) ->
                val (g, pattern) = key.split(':')
                g == group && pattern.startsWith("*") && name.endsWith(pattern.removePrefix("*"))
            }?.value

fun applySecurityFloors(configurations: ConfigurationContainer) =
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val floor = floorFor(requested.group, requested.name) ?: return@eachDependency
            val version = requested.version?.takeIf { it.isNotEmpty() } ?: return@eachDependency
            if (isBelow(version, floor)) {
                // Guava only publishes "-android" and "-jre" versions; its Gradle metadata picks the right jar for
                // the classpath either way, so an unsuffixed request (e.g. "18.0") gets "-jre".
                val flavour =
                    if (requested.name == "guava") version.substringAfter('-', "").ifEmpty { "jre" } else null
                useVersion(if (flavour != null) "$floor-$flavour" else floor)
                because("security floor (settings.gradle.kts)")
            }
        }
    }

gradle.beforeProject {
    applySecurityFloors(buildscript.configurations)
    applySecurityFloors(configurations)
}

rootProject.name = "seion"

include(":app")
