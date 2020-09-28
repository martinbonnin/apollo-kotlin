package com.apollographql.apollo.cli

import com.apollographql.apollo.compiler.parser.introspection.IntrospectionSchema
import com.oracle.svm.core.annotate.AutomaticFeature
import com.oracle.svm.core.jdk.RuntimeFeature
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import io.github.classgraph.ClassGraph
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection
import java.lang.reflect.ParameterizedType

@AutomaticFeature
internal class RuntimeReflectionRegistrationFeature : Feature {
  override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
    try {
      val pkg = "com.apollographql.apollo"
      ClassGraph()
          //.verbose() // Log to stderr
          .enableAnnotationInfo()
          .enableMethodInfo()
          .enableClassInfo() // Scan classes, methods, fields, annotations
          .acceptPackages(pkg) // Scan com.xyz and subpackages (omit to scan all packages)
          .scan()
          .use { scanResult ->                    // Start the scan
            for (classInfo in scanResult.getSubclasses(JsonAdapter::class.java.name)) {
              registerMoshiAdapter(classInfo.loadClass())
            }
          }
    } catch (e: Exception) {
      e.printStackTrace()
      throw e
    }
    RuntimeReflection.register(IntrospectionSchema.Kind::class.java)
    RuntimeReflection.register(*IntrospectionSchema.Kind::class.java.fields)
  }

  private fun registerMoshiAdapter(java: Class<*>) {
    RuntimeReflection.register(java)
    java.methods.forEach {
      RuntimeReflection.register(it)
    }
    val superclass = java.genericSuperclass as ParameterizedType
    // extends JsonAdapter<X>()
    val valueType = superclass.actualTypeArguments.first()
    if (valueType is Class<*>) {
      valueType.constructors.forEach {
        RuntimeReflection.register(it)
      }
    }
    try {
      RuntimeReflection.register(java.getConstructor(Moshi::class.java))
    } catch (nsme: NoSuchMethodException) {
      // expected
    }
  }
}
