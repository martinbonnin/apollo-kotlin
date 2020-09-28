plugins {
  kotlin("jvm")
  id("application")
}

application {
  mainClassName = "com.apollographql.apollo.cli.MainKt"
}

dependencies {
  implementation(project(":apollo-compiler"))
  implementation(groovy.util.Eval.x(project, "x.dep.clikt"))
  implementation(groovy.util.Eval.x(project, "x.dep.moshi.moshi"))
  compileOnly("org.graalvm.nativeimage:svm:20.2.0")
  implementation("io.github.classgraph:classgraph:4.8.87")
}

tasks.create("fatJar", Jar::class.java) {
  manifest {
    attributes.set("Main-Class", "com.apollographql.apollo.cli.MainKt")
  }
  archiveBaseName.set("apollo-cli-fat.jar")
  from (
    configurations.runtimeClasspath.get().map {
      if (it.isDirectory) {
        it
      } else {
        zipTree(it)
      }
    }
  )
  with(tasks.named("jar").get() as Jar)
}
