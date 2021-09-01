package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.squareup.javapoet.AnnotationSpec
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T

internal fun deprecatedAnnotation(message: String) = AnnotationSpec
    .builder(JavaClassNames.Deprecated)
    .apply {
      if (message.isNotBlank()) {
        addMember("message = $S", message)
      }
    }
    .build()