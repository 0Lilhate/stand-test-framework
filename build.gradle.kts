import ru.alfalab.gradle.codestyle.CodeStyleExtension
import ru.vyarus.gradle.plugin.quality.QualityExtension

plugins {
  id("ru.alfalab.library-configurer") version "10.0.4" apply false
  id("ru.alfalab.semantic-version") version "2.0.0"
}

apply(plugin = "ru.alfalab.codestyle")
apply(plugin = "org.sonarqube")

configure<CodeStyleExtension> {
  configs.editorconfig.apply {
    append("")
    append("[*]")
    append("end_of_line = lf")
    append("insert_final_newline = true")
    append("")
    append("[*.{yml,yaml,json,toml,kts}]")
    append("indent_size = 2")
    append("")
    append("[*.md]")
    append("trim_trailing_whitespace = false")
  }
}

val javaRelease: Int = libs.versions.javaRelease.get().toInt()

subprojects {
  if (name == "stand-test-bom") {
    return@subprojects
  }

  apply(plugin = "ru.alfalab.library-configurer")

  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion))
    }
    withJavadocJar()
  }

  tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaRelease)
  }

  tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
  }

  extensions.configure<QualityExtension> {
    strict.set(true)
  }
}

tasks.register("dockerCreateDockerfile") {
  val dockerfile = layout.buildDirectory.file("docker/Dockerfile")
  val content = """
      # ЗАГЛУШКА, а не описание образа.
      #
      # stand-test-framework — тестовый SDK: он поставляется 13 maven-артефактами и никакого образа не
      # публикует. Микросервисный пайплайн всё равно требует Docker-контекст, и этот файл существует
      # ровно для того, чтобы его проверка проходила.
      #
      # Если из этого контекста собрался и уехал в реестр образ — это ошибка конфигурации джобы, а не
      # намерение: библиотеке нужен библиотечный пайплайн либо гейт на docker-стадиях.
      FROM scratch
      LABEL ru.alfa.stand.test.placeholder="true"
      LABEL description="Placeholder: stand-test-framework ships maven artifacts, not a docker image."
  """.trimIndent() + "\n"

  group = "docker"
  description = "Writes a placeholder build/docker/Dockerfile so the microservice CI pipeline finds a docker context."
  outputs.file(dockerfile)
  inputs.property("content", content)
  doLast {
    val target = dockerfile.get().asFile
    target.parentFile.mkdirs()
    target.writeText(content)
  }
}
