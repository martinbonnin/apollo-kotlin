plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.google.devtools.ksp")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("com.gradleup.gratatouille.implementation").apply(false)
}

apolloLibrary(
    javaModuleName = "com.apollographql.apollo3.gradle",
    jvmTarget = 11 // AGP requires 11
)

plugins.apply("com.gradleup.gratatouille.implementation")

dependencies {
  implementation(project(":apollo-compiler"))
  implementation(project(":apollo-tooling"))
  implementation(project(":apollo-ast"))
  implementation(libs.asm)
  implementation(libs.kotlinx.serialization.json)
}
