import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// Root project is a pure aggregator for the stand-test SDK modules.
// All shared configuration lives in the `subprojects { }` block below plus the version
// catalog (gradle/libs.versions.toml) — mirroring the pakt-lgot-service style
// (no buildSrc / build-logic / precompiled convention plugins).
//
// Build toolchain note: this project runs on Gradle 9.3.0 (the reference pakt-lgot-service
// is on 8.14.4) — a deliberate, pre-existing scaffold choice we keep. Plugin versions only
// RESERVED in the catalog for later phases (springBoot, springDependencyManagement) must be
// re-validated for Gradle 9 before they are actually applied.

allprojects {
  group = rootProject.group
  version = rootProject.version
}

subprojects {
  // stand-test-bom is a `java-platform` project and must NOT receive the java-library /
  // checkstyle / test configuration: `java-platform` is mutually exclusive with `java`.
  // The BOM configures itself in stand-test-bom/build.gradle.kts.
  if (name == "stand-test-bom") {
    return@subprojects
  }

  apply(plugin = "java-library")
  apply(plugin = "checkstyle")
  apply(plugin = "maven-publish")
  apply(plugin = "jacoco")

  val catalog = rootProject.extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")

  fun ver(alias: String): String =
    catalog.findVersion(alias).orElseThrow { error("Missing version: $alias") }.requiredVersion

  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(ver("java").toInt()))
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    // Compile with the JDK-24 toolchain but target Java 17 bytecode/API (`--release 17`), so the SDK is
    // loadable by consumers on JDK 17/21/24 (plan §14). `--release` also bans APIs newer than 17, keeping
    // the sources 17-compatible.
    options.release.set(ver("javaRelease").toInt())
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
      events("passed", "failed", "skipped")
    }
  }

  plugins.withId("checkstyle") {
    extensions.configure<CheckstyleExtension> {
      toolVersion = ver("checkstyle")
      config = resources.text.fromFile(rootProject.file("checkstyle.xml"))
      configProperties["basedir"] = rootProject.projectDir.absolutePath
      isShowViolations = true
      maxWarnings = 0
    }

    tasks.withType<Checkstyle>().configureEach {
      include("**/*.java")
      classpath = files()
      reports {
        xml.required.set(false)
        html.required.set(true)
      }
    }

    tasks.named<Checkstyle>("checkstyleMain") {
      setSource("src/main/java")
    }
    tasks.named<Checkstyle>("checkstyleTest") {
      setSource("src/test/java")
    }

    tasks.named("check") {
      dependsOn(tasks.withType<Checkstyle>())
    }
  }

  // JaCoCo coverage gate (DoD: instruction coverage >= 80%). Modules without execution data
  // (skeletons whose `test` task is NO-SOURCE) are skipped via `onlyIf`, so the build stays green
  // until they gain real tests. toolVersion is pinned via the catalog because the Java 24 toolchain
  // needs JaCoCo >= 0.8.13 to parse class-file major version 68.
  plugins.withId("jacoco") {
    extensions.configure<JacocoPluginExtension> {
      toolVersion = ver("jacoco")
    }

    tasks.withType<JacocoReport>().configureEach {
      dependsOn(tasks.withType<Test>())
      reports {
        xml.required.set(true)
        html.required.set(true)
      }
      onlyIf { executionData.files.any { it.exists() } }
    }

    tasks.withType<JacocoCoverageVerification>().configureEach {
      dependsOn(tasks.withType<Test>())
      onlyIf { executionData.files.any { it.exists() } }
      violationRules {
        rule {
          limit {
            counter = "INSTRUCTION"
            minimum = "0.80".toBigDecimal()
          }
        }
      }
    }

    tasks.withType<Test>().configureEach {
      finalizedBy(tasks.withType<JacocoReport>())
    }

    tasks.named("check") {
      dependsOn(tasks.withType<JacocoCoverageVerification>())
    }
  }

  plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
      publications {
        create<MavenPublication>("maven") {
          from(components["java"])
        }
      }
      // No publishing repository is configured yet — the internal Nexus/Artifactory URL is
      // intentionally deferred. `./gradlew publishToMavenLocal` works for local validation.
    }
  }
}
