/*
 * Generates ResponseAdapters for variables/input
 */
package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.fromJson
import com.apollographql.apollo3.compiler.codegen.Identifier.toJson
import com.apollographql.apollo3.compiler.codegen.Identifier.value
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.codegen.java.helpers.NamedType
import com.apollographql.apollo3.compiler.codegen.java.helpers.writeToResponseCodeBlock
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asTypeName


internal fun List<NamedType>.inputAdapterTypeSpec(
    context: JavaContext,
    adapterName: String,
    adaptedTypeName: TypeName,
): TypeSpec {
  return TypeSpec.objectBuilder(adapterName)
      .addSuperinterface(Adapter::class.asTypeName().parameterizedBy(adaptedTypeName))
      .addMethod(notImplementedFromResponseMethodSpec(adaptedTypeName))
      .addMethod(writeToResponseMethodSpec(context, adaptedTypeName))
      .build()
}

private fun notImplementedFromResponseMethodSpec(adaptedTypeName: TypeName) = MethodSpec.methodBuilder(fromJson)
    .addAnnotation(JavaClassNames.Override)
    .addParameter(Identifier.reader, JsonReader::class)
    .addParameter(customScalarAdapters, JavaClassNames.CustomScalarAdapters)
    .returns(adaptedTypeName)
    .addCode("throw %T(%S)", ClassName.get("kotlin", "IllegalStateException"), "Input type used in output position")
    .build()


private fun List<NamedType>.writeToResponseMethodSpec(
    context: JavaContext,
    adaptedTypeName: TypeName,
): MethodSpec {
  return MethodSpec.methodBuilder(toJson)
      .addAnnotation(JavaClassNames.Override)
      .addParameter(writer, JavaClassNames.JsonWriter)
      .addParameter(customScalarAdapters, CustomScalarAdapters::class)
      .addParameter(value, adaptedTypeName)
      .addCode(writeToResponseCodeBlock(context))
      .build()
}


