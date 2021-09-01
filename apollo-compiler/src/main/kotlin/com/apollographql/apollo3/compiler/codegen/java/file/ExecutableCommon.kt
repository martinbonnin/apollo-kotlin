package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.selections
import com.apollographql.apollo3.compiler.codegen.Identifier.serializeVariables
import com.apollographql.apollo3.compiler.codegen.Identifier.toJson
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.T
import com.apollographql.apollo3.compiler.codegen.java.adapter.objectAdapterInitializer
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName

fun serializeVariablesMethodSpec(
    adapterClassName: TypeName?,
    emptyMessage: String
): MethodSpec {

  val body = if (adapterClassName == null) {
    CodeBlock.of("// $emptyMessage\n")
  } else {
    CodeBlock.of(
        "$L.$toJson($writer, $customScalarAdapters, this)",
            CodeBlock.of(T, adapterClassName)
    )
  }
  return MethodSpec.methodBuilder(serializeVariables)
      .addAnnotation(JavaClassNames.Override)
      .addParameter(JavaClassNames.JsonWriter, writer)
      .addParameter(JavaClassNames.CustomScalarAdapters, customScalarAdapters)
      .addCode(body)
      .build()
}

fun adapterMethodSpec(
    adapterTypeName: TypeName,
    adaptedTypeName: TypeName
): MethodSpec {
  return MethodSpec.methodBuilder(Identifier.adapter)
      .addAnnotation(JavaClassNames.Override)
      .returns(ParameterizedTypeName.get(JavaClassNames.Adapter, adaptedTypeName))
      .addCode(
          "return $L;\n",
          objectAdapterInitializer(
              adapterTypeName,
              adaptedTypeName,
              false,
          )
      )
      .build()
}

fun selectionsMethodSpec(context: JavaContext, className: ClassName): MethodSpec {
  return MethodSpec.methodBuilder(selections)
      .addAnnotation(JavaClassNames.Override)
      .returns(ParameterizedTypeName.get(JavaClassNames.List, JavaClassNames.CompiledSelection))
      .addCode("return $T.$L;\n", className, context.layout.rootSelectionsPropertyName())
      .build()
}
