plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-gradle-plugin")
  id("com.gradle.plugin-publish")
  id("com.gradleup.gratatouille.plugin")
}

apolloLibrary(
    javaModuleName = null,
    jvmTarget = 11 // AGP requires 11
)

dependencies {
  compileOnly(libs.gradle.api.min)
  compileOnly(libs.kotlin.plugin.min)
  compileOnly(libs.android.plugin.min)

  api(project(":apollo-annotations"))
  gratatouille(project(":apollo-gradle-plugin-external"))

  testImplementation(project(":apollo-ast"))
  testImplementation(project(":apollo-execution-incubating"))
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.assertj)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.okhttp.tls)
  testImplementation(platform(libs.http4k.bom.get()))
  testImplementation(libs.http4k.core)
  testImplementation(libs.http4k.server.jetty)
  testImplementation(libs.slf4j.get().toString()) {
    because("jetty uses SL4F")
  }
}


gradlePlugin {
  website.set("https://github.com/apollographql/apollo-kotlin")
  vcsUrl.set("https://github.com/apollographql/apollo-kotlin")

  plugins {
    create("com.apollographql.apollo3") {
      id = "com.apollographql.apollo3"
      displayName = "Apollo Kotlin GraphQL client plugin."
      description = "Automatically generates typesafe java and kotlin models from your GraphQL files."
      implementationClass = "com.apollographql.apollo3.gradle.internal.ApolloPlugin"
      tags.set(listOf("graphql", "apollo"))
    }
  }
  plugins {
    create("com.apollographql.apollo3.external") {
      id = "com.apollographql.apollo3.external"
      displayName = "Deprecated external plugin."
      description = "Deprecated"
      implementationClass = "com.apollographql.apollo3.gradle.internal.ApolloPlugin"
      tags.set(listOf("graphql", "apollo"))
    }
  }
}

/**
 * This is so that the plugin marker pom contains a <scm> tag
 * It was recommended by the Gradle support team.
 */
configure<PublishingExtension> {
  publications.configureEach {
    if (name == "apolloGradlePluginPluginMarkerMaven") {
      this as MavenPublication
      pom {
        scm {
          url.set(findProperty("POM_SCM_URL") as String?)
          connection.set(findProperty("POM_SCM_CONNECTION") as String?)
          developerConnection.set(findProperty("POM_SCM_DEV_CONNECTION") as String?)
        }
      }
    }
  }
}

tasks.register("cleanStaleTestProjects") {
  /**
   * Remove stale testProject directories
   */
  val buildFiles = layout.buildDirectory.asFile.get().listFiles()
  doFirst {
    buildFiles?.forEach {
      if (it.isDirectory && it.name.startsWith("testProject")) {
        it.deleteRecursively()
      }
    }
  }
}

tasks.withType<Test> {
  dependsOn(":apollo-annotations:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-api:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-ast:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-normalized-cache-api:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-mpp-utils:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-compiler:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-gradle-plugin-external:publishAllPublicationsToPluginTestRepository")
  dependsOn(":apollo-tooling:publishAllPublicationsToPluginTestRepository")
  dependsOn("publishAllPublicationsToPluginTestRepository")

  dependsOn("cleanStaleTestProjects")

  addRelativeInput("testFiles", "testFiles")
  addRelativeInput("testProjects", "testProjects")

  maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
}

val allTests = tasks.create("allTests")
tasks.check {
  dependsOn(allTests)
}

fun createTests(javaVersion: Int) {
  val sourceSet = sourceSets.create("test-java$javaVersion")

  configurations[sourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
  configurations[sourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

  dependencies.add(sourceSet.implementationConfigurationName, sourceSets.getByName("test").output)
  dependencies.add(sourceSet.implementationConfigurationName, sourceSets.getByName("main").output)

  val task = tasks.register<Test>("testJava$javaVersion") {
    description = "Runs integration tests for Java $javaVersion."
    group = "verification"
    useJUnit()

    testClassesDirs = sourceSet.output.classesDirs
    classpath = configurations[sourceSet.runtimeClasspathConfigurationName] + sourceSet.output + sourceSets.getByName("main").output

    environment("APOLLO_RELOCATE_JAR", System.getenv("APOLLO_RELOCATE_JAR"))
    setTestToolchain(project, this, javaVersion)
  }

  allTests.dependsOn(task)
}

tasks.named("test").configure {
  // Disable the default tests, they are empty
  enabled = false
}

listOf(11, 17).forEach { javaVersion ->
  createTests(javaVersion)
}

tasks.register("acceptAndroidLicenses") {
  val source = rootProject.file("android-licenses/android-sdk-preview-license")
  val target = rootProject.file("${System.getenv("ANDROID_HOME")}/licenses/android-sdk-preview-license")
  doLast {
    source.copyTo(target, overwrite = true)
  }
}

tasks.named("testJava17").configure {
  dependsOn("acceptAndroidLicenses")
}
