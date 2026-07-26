import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.graalvmNative)
  application
}

application {
  mainClass.set("com.linroid.ketch.cli.MainKt")
}

graalvmNative {
  toolchainDetection.set(true)
  binaries {
    named("main") {
      imageName.set("ketch")
      mainClass.set("com.linroid.ketch.cli.MainKt")
      javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
          languageVersion.set(JavaLanguageVersion.of(21))
          vendor.set(JvmVendorSpec.ORACLE)
        }
      )
      buildArgs.addAll(
        "--no-fallback",
        "-Ob",
        "-H:+ReportExceptionStackTraces",
        "--initialize-at-build-time=io.ktor,kotlin,kotlinx.coroutines,kotlinx.serialization,okio",
        "--initialize-at-build-time=ch.qos.logback",
        "--initialize-at-build-time=org.slf4j",
        "--initialize-at-run-time=kotlin.uuid.SecureRandomHolder",
        "-H:IncludeResources=logback.xml",
      )
      if (!Os.isFamily(Os.FAMILY_MAC)) {
        buildArgs.add("-H:+StripDebugInfo")
      }
    }
  }
}

dependencies {
  implementation(projects.config)
  implementation(projects.library.mcp)
  implementation(projects.library.core)
  implementation(projects.library.sqlite)
  implementation(projects.library.ktor)
  implementation(projects.library.ftp)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.logback)
}
