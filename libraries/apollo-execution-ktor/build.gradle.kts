plugins {
  id("org.jetbrains.kotlin.multiplatform")
}

apolloLibrary(
    namespace = "com.apollographql.apollo3.execution",
    withJs = false,
    withApple = false,
    withWasm = false,
)

kotlin {
  sourceSets {
    getByName("commonMain") {
      dependencies {
        api(project(":apollo-execution-incubating"))
        implementation(libs.atomicfu.library)
        api(libs.kotlinx.coroutines)
        api(libs.ktor.server.core)
      }
    }
    getByName("jvmTest") {
      dependencies {
        implementation(libs.ktor.server.netty.jvm)
        implementation(libs.ktor.server.cors)
        implementation(libs.slf4j)
      }
    }
  }
}

