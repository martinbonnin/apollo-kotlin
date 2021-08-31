package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asClassName

internal fun TypeSpec.patchKotlinNativeOptionalArrayProperties(): TypeSpec {
  if (kind != TypeSpec.Kind.CLASS) {
    return this
  }

  val patchedNestedTypes = typeSpecs.map { type ->
    if (type.kind == TypeSpec.Kind.CLASS) {
      type.patchKotlinNativeOptionalArrayProperties()
    } else {
      type
    }
  }

  val nonOptionalListPropertyAccessors = FieldSpecs
      .filter { FieldSpec ->
        val propertyType = FieldSpec.type
        propertyType is ParameterizedTypeName &&
            propertyType.rawType == JavaClassNames.List &&
            propertyType.typeArguments.single().isNullable
      }
      .map { FieldSpec ->
        val listItemType = (FieldSpec.type as ParameterizedTypeName).typeArguments.single().copy(nullable = false)
        val nonOptionalListType = JavaClassNames.List.parameterizedBy(listItemType).copy(nullable = FieldSpec.type.isNullable)
        MethodSpec
            .builder("${FieldSpec.name}FilterNotNull")
            .returns(nonOptionalListType)
            .addStatement("return %L%L.filterNotNull()", FieldSpec.name, if (FieldSpec.type.isNullable) "?" else "")
            .build()
      }
  return toBuilder()
      .addMethods(nonOptionalListPropertyAccessors)
      .apply { typeSpecs.clear() }
      .addTypes(patchedNestedTypes)
      .build()
}