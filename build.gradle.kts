allprojects {
  group = rootProject.group
  version = rootProject.version
}

subprojects {
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
    withSourcesJar()
    if (project.name != "stand-test-example") {
      withJavadocJar()
    }
  }

  tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
  }

  tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
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
    fun prop(gradleName: String, envName: String): String? = providers.gradleProperty(gradleName)
      .orElse(providers.environmentVariable(envName))
      .orNull

    val isSnapshot = version.toString().endsWith("-SNAPSHOT")
    val commonUrl = prop("standTestPublishUrl", "STAND_TEST_PUBLISH_URL")
    val repoUrl = if (isSnapshot) {
      prop("standTestPublishSnapshotsUrl", "STAND_TEST_PUBLISH_SNAPSHOTS_URL") ?: commonUrl
    } else {
      prop("standTestPublishReleasesUrl", "STAND_TEST_PUBLISH_RELEASES_URL") ?: commonUrl
    }

    extensions.configure<PublishingExtension> {
      if (project.name != "stand-test-example") {
        publications {
          create<MavenPublication>("maven") {
            from(components["java"])
            pom {
              name.set(project.name)
              description.set(project.description ?: "stand-test SDK module '${project.name}'")
            }
          }
        }
      }

      if (repoUrl != null) {
        val repoUsername = prop("standTestPublishUsername", "STAND_TEST_PUBLISH_USERNAME")
        val repoPassword = prop("standTestPublishPassword", "STAND_TEST_PUBLISH_PASSWORD")
        val allowInsecure = prop("standTestPublishAllowInsecure", "STAND_TEST_PUBLISH_ALLOW_INSECURE").toBoolean()
        repositories {
          maven {
            name = "internal"
            url = uri(repoUrl)
            isAllowInsecureProtocol = allowInsecure
            if (repoUsername != null) {
              credentials {
                username = repoUsername
                password = repoPassword
              }
            }
          }
        }
      }
    }

    if (repoUrl == null) {
      tasks.named("publish") {
        doFirst {
          throw GradleException(
            "No publish repository configured: set -PstandTestPublishUrl=<repo> (or STAND_TEST_PUBLISH_URL, "
              + "or the releases+snapshots pair — see docs/publishing.md). `publishToMavenLocal` works without it.")
        }
      }
    }
  }
}
