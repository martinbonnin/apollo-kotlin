plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.google.devtools.ksp")
  id("com.apollographql.apollo3")
}

apolloTest()

dependencies {
  implementation(libs.apollo.execution)
  ksp(libs.apollo.ksp)
}

ksp {
  arg("apolloService", "demo")
  arg("apolloPackageName", "demo")
}

