import java.util.zip.ZipFile

plugins {
    id("fabric-loom")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

version = property("mod_version") as String
group = property("group") as String

base {
    archivesName.set("phantom")
}

val clientPublicLayer by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Public-layer client mod (mixins + API + native), consumed from mavenLocal.
    // Merged into phantom.jar so the loader owns the single Fabric mod id.
    modImplementation("org.phantom:phantom-client-public:${property("phantom_client_version")}")
    clientPublicLayer("org.phantom:phantom-client-public:${property("phantom_client_version")}")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serialization_version")}")
    include("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serialization_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        clientPublicLayer.map { zipTree(it) }
    }) {
        // The public-layer artifact is supposed to be pre-stripped, but be defensive:
        // refuse to bundle anything under org/phantom/internal/** regardless of what
        // the upstream artifact contains. The verifyNoProtectedSource task below is
        // the final fail-closed gate.
        exclude(
            "fabric.mod.json",
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "org/phantom/internal/**"
        )
    }
}

// Fail-closed verification: scan the final phantom.jar and abort the build if any
// protected bytecode (org/phantom/internal/**) made it in. This is a second line of
// defense beyond the upstream `clientPublicApiJar` exclude and the `jar` task's own
// exclude — it catches an outdated mavenLocal artifact, a misconfigured upstream
// build, or any future regression that would leak the protected source.
val verifyNoProtectedSource = tasks.register("verifyNoProtectedSource") {
    group = "verification"
    description = "Fails if phantom.jar contains any org/phantom/internal/** classes."

    val jarTask = tasks.named<Jar>("jar")
    dependsOn(jarTask)
    inputs.files(jarTask.map { it.archiveFile })
    outputs.upToDateWhen { false }

    doLast {
        val jarFile = jarTask.get().archiveFile.get().asFile
        if (!jarFile.exists()) {
            throw GradleException("Loader jar does not exist: ${jarFile.path}")
        }

        val leaked = mutableListOf<String>()
        ZipFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("org/phantom/internal/")) {
                    leaked += name
                }
            }
        }

        if (leaked.isNotEmpty()) {
            val preview = leaked.take(20).joinToString("\n  ") { "- $it" }
            val suffix = if (leaked.size > 20) "\n  ... and ${leaked.size - 20} more" else ""
            throw GradleException(
                "Protected source leaked into ${jarFile.name} (${leaked.size} entries):\n  $preview$suffix\n" +
                    "The loader jar must not ship org/phantom/internal/** classes. " +
                    "Check phantom-client-public publication and the jar task excludes."
            )
        }

        logger.lifecycle("verifyNoProtectedSource: OK — ${jarFile.name} contains no org/phantom/internal/** entries.")
    }
}

tasks.named("build") {
    dependsOn(verifyNoProtectedSource)
}

tasks.named<Jar>("jar") {
    finalizedBy(verifyNoProtectedSource)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
