plugins {
  `java-library`
  kotlin("multiplatform")
}

kotlin {
  ios()

  jvm {
    withJava()
  }

  js {
    useCommonJs()
    browser()
    nodejs()
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(groovy.util.Eval.x(project, "x.dep.okio"))
      }
    }

    val jvmMain by getting {
      dependsOn(commonMain)
      dependencies {
      }
    }

    val jsMain by getting {
      dependsOn(commonMain)
      dependencies {
        implementation(npm("big.js", "5.2.2"))
      }
    }

    val jsTest by getting {
      dependencies {
        implementation(kotlin("test-js"))
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }

    val jvmTest by getting {
      dependsOn(jvmMain)
      dependencies {
        implementation(kotlin("test-junit"))
        implementation(groovy.util.Eval.x(project, "x.dep.truth"))
        implementation(groovy.util.Eval.x(project, "x.dep.okHttp.okHttp"))
      }
    }
  }
}

tasks.withType<Checkstyle> {
  exclude("**/BufferedSourceJsonReader.java")
  exclude("**/JsonScope.java")
  exclude("**/JsonUtf8Writer.java")
}

tasks.named("javadoc").configure {
  /**
   * Somehow Javadoc fails when I removed the `@JvmSynthetic` annotation from `InputFieldWriter.ListItemWriter.writeList`
   * It fails with `javadoc: error - String index out of range: -1`
   * Javadoc from JDK 13 works fine
   * I'm not sure how to fix it so this ignores the error. The uploaded javadoc.jar will be truncated and only contain the
   * classes that have been written successfully before Javadoc fails.
   */
  (this as Javadoc).isFailOnError = false
}
