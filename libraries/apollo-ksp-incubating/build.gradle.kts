plugins {
  id("org.jetbrains.kotlin.jvm")
}

apolloLibrary(
    namespace = "com.apollographql.apollo3.ksp",
)

dependencies {
  implementation(project(":apollo-compiler"))
  implementation(libs.ksp.api)
  implementation(project(":apollo-ast"))
  testImplementation(libs.kotlin.test)
}
