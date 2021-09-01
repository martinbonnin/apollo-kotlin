package com.apollographql.apollo3.compiler.codegen.java.helpers


import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.TypeSpec
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T

internal fun TypeSpec.Builder.maybeAddDescription(description: String?): TypeSpec.Builder {
  if (description.isNullOrBlank()) {
    return this
  }

  return addJavadoc("$L", description)
}

internal fun FieldSpec.Builder.maybeAddDescription(description: String?): FieldSpec.Builder {
  if (description.isNullOrBlank()) {
    return this
  }

  return addJavadoc("$L", description)
}

internal fun TypeSpec.Builder.maybeAddDeprecation(deprecationReason: String?): TypeSpec.Builder {
  if (deprecationReason.isNullOrBlank()) {
    return this
  }

  return addAnnotation(deprecatedAnnotation(deprecationReason))
}

internal fun FieldSpec.Builder.maybeAddDeprecation(deprecationReason: String?): FieldSpec.Builder {
  if (deprecationReason.isNullOrBlank()) {
    return this
  }

  return addAnnotation(deprecatedAnnotation(deprecationReason))
}
