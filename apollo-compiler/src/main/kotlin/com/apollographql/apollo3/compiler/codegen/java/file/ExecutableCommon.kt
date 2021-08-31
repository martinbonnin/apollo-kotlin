package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CompiledSelection
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.selections
import com.apollographql.apollo3.compiler.codegen.Identifier.serializeVariables
import com.apollographql.apollo3.compiler.codegen.Identifier.toJson
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.codegen.java.adapter.obj
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.helpers.patchKotlinNativeOptionalArrayProperties
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asClassName
import com.squareup.javapoet.asTypeName

fun serializeVariablesMethodSpec(
    adapterClassName: TypeName?,
    emptyMessage: String
): MethodSpec {

  val body = if (adapterClassName == null) {
    CodeBlock.of("""
      // $emptyMessage
    """.trimIndent())
  } else {
    CodeBlock.of(
        "%L.$toJson($writer, $customScalarAdapters, this)",
            CodeBlock.of("%T", adapterClassName)
    )
  }
  return MethodSpec.builder(serializeVariables)
      .addModifiers(KModifier.OVERRIDE)
      .addParameter(writer, JsonWriter::class)
      .addParameter(customScalarAdapters, CustomScalarAdapters::class.asTypeName())
      .addCode(body)
      .build()
}

fun adapterMethodSpec(
    adapterTypeName: TypeName,
    adaptedTypeName: TypeName
): MethodSpec {
  return MethodSpec.builder("adapter")
      .addModifiers(KModifier.OVERRIDE)
      .returns(Adapter::class.asClassName().parameterizedBy(adaptedTypeName))
      .addCode(CodeBlock.of("return·%T", adapterTypeName).obj(false))
      .build()
}

fun selectionsMethodSpec(context: JavaContext, className: ClassName): MethodSpec {
  return MethodSpec.builder(selections)
      .addModifiers(KModifier.OVERRIDE)
      .returns(List::class.parameterizedBy(CompiledSelection::class))
      .addCode("return %T.%L\n", className, context.layout.rootSelectionsPropertyName())
      .build()
}

fun TypeSpec.maybeAddFilterNotNull(generateFilterNotNull: Boolean): TypeSpec {
  if (!generateFilterNotNull) {
    return this
  }
  return patchKotlinNativeOptionalArrayProperties()
}