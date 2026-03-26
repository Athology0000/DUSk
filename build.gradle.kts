import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin)
  alias(libs.plugins.loom)
  `maven-publish`
}

val baseGroup: String by project
val modVersion: String by project
val modName: String by project

version = modVersion
group = baseGroup

base {
  archivesName.set(modName)
}

repositories {
  mavenCentral()
  maven("https://maven.meteordev.org/releases")
  maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}

dependencies {
  minecraft(libs.minecraft)
  mappings(loom.officialMojangMappings())
  modImplementation(libs.bundles.fabric)

  implementation(libs.nanovg) { include(this) }
  implementation(libs.bundles.included) { include(this) }

  listOf("windows", "linux", "macos", "macos-arm64").forEach {
    implementation(variantOf(libs.nanovg) { classifier("natives-$it") }) {
      include(this)
    }
  }

  runtimeOnly(libs.httpclient)
  modRuntimeOnly(libs.devauth)
}

tasks {
  processResources {
    val fabricKotlinVersion = libs.versions.fabric.kotlin.get()
    val fabricLoaderVersion = libs.versions.fabric.loader.get()
    val minecraftVersion = libs.versions.minecraft.version.get()

    inputs.property("version", project.version)
    inputs.property("fabricKotlinVersion", fabricKotlinVersion)
    inputs.property("fabricLoaderVersion", fabricLoaderVersion)
    inputs.property("minecraftVersion", minecraftVersion)

    filesMatching("fabric.mod.json") {
      expand(
        "version" to project.version,
        "fabricKotlinVersion" to fabricKotlinVersion,
        "fabricLoaderVersion" to fabricLoaderVersion,
        "minecraftVersion" to minecraftVersion,
      )
    }
  }

  compileKotlin {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

// ── Native DLL build (Windows, requires VS2026 + CMake) ───────────────────────
val nativesDir = file("natives")
val dllReleasePath = file("natives/build/Release/cobalt_pathfinder.dll")
val dllResourceDest = file("src/main/resources/natives/windows")

tasks.register<Exec>("buildNative") {
  group = "build"
  description = "Compiles cobalt_pathfinder.dll via CMake."
  workingDir = nativesDir
  commandLine(
    "cmd", "/c",
    "cmake --build build --config Release"
  )
  onlyIf { nativesDir.resolve("build").exists() }
}

tasks.register<Copy>("copyNativeDll") {
  group = "build"
  description = "Copies the built cobalt_pathfinder.dll into resources."
  dependsOn("buildNative")
  from(dllReleasePath)
  into(dllResourceDest)
  onlyIf { dllReleasePath.exists() }
}

tasks.named("processResources") {
  dependsOn("copyNativeDll")
}

// ── Deploy JAR to Prism mods folder ──────────────────────────────────────────
val modsDir = file("C:/Users/aeare/AppData/Roaming/PrismLauncher/instances/1.21.11(1)/minecraft/mods")

tasks.register<Copy>("deployMod") {
  group = "build"
  description = "Copies the built JAR to the Prism Launcher mods folder."
  dependsOn("build")
  from(layout.buildDirectory.dir("libs")) {
    include("${modName}-${version}.jar")
  }
  into(modsDir)
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// ─────────────────────────────────────────────────────────────────────────────

tasks.register<Copy>("collectObfLibs") {
  group = "build"
  description = "Copies compile classpath jars to build/obf-libs for Skidfuscator -li."
  from(configurations["compileClasspath"].resolvedConfiguration.resolvedArtifacts.map { it.file })
  into(layout.buildDirectory.dir("obf-libs"))
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
