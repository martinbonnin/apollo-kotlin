package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.ir.IrModel
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asTypeName

/**
 * For responseBased codegen, generates an adapter for an implementation
 */
class ImplementationAdapterBuilder(
    val context: JavaContext,
    val model: IrModel,
    val path: List<String>
) {
  private val adapterName = model.modelName
  private val adaptedClassName by lazy {
    context.resolver.resolveModel(model.id)
  }

  private val nestedAdapterBuilders = model.modelGroups.map {
    ResponseAdapterBuilder.create(
        context,
        it,
        path + adapterName,
        false
    )
  }

  fun prepare() {
    nestedAdapterBuilders.map { it.prepare() }
  }

  fun build(): TypeSpec {
    return TypeSpec.objectBuilder(adapterName)
        .addField(responseNamesFieldSpec(model))
        .addMethod(readFromResponseMethodSpec())
        .addMethod(writeToResponseMethodSpec())
        .addTypes(nestedAdapterBuilders.flatMap { it.build() })
        .build()

  }

  private fun readFromResponseMethodSpec(): MethodSpec {
    return MethodSpec.methodBuilder(Identifier.fromJson)
        .returns(adaptedClassName)
        .addParameter(Identifier.reader, JsonReader::class)
        .addParameter(Identifier.customScalarAdapters, CustomScalarAdapters::class)
        .addParameter(Identifier.typename, String::class)
        .addCode(readFromResponseCodeBlock(model, context, true))
        .build()
  }

  private fun writeToResponseMethodSpec(): MethodSpec {
    return MethodSpec.methodBuilder(Identifier.toJson)
        .addParameter(Identifier.writer, JavaClassNames.JsonWriter)
        .addParameter(Identifier.customScalarAdapters, CustomScalarAdapters::class)
        .addParameter(Identifier.value, adaptedClassName)
        .addCode(writeToResponseCodeBlock(model, context))
        .build()
  }
}