package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.S
import com.squareup.javapoet.AnnotationSpec

internal fun deprecatedAnnotation(message: String) = AnnotationSpec
    .builder(JavaClassNames.Deprecated)
    .apply {
      if (message.isNotBlank()) {
        addMember("message = $S", message)
      }
    }
    .build()