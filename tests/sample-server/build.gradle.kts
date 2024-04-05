plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.google.devtools.ksp")
  id("com.apollographql.apollo3")
}

apolloTest()

dependencies {
  implementation(libs.apollo.execution)
  implementation(libs.apollo.api)
  implementation(libs.kotlinx.coroutines)
  implementation(libs.atomicfu.library)

  implementation(platform(libs.http4k.bom.get()))
  implementation(libs.http4k.core)
  implementation(libs.http4k.server.jetty)
  implementation(libs.slf4j.get().toString()) {
    because("jetty uses SL4F")
  }

  ksp(libs.apollo.ksp)
}

ksp {
  this.arg("apolloService", "sampleserver")
  this.arg("apolloPackageName", "sample.server")
}

