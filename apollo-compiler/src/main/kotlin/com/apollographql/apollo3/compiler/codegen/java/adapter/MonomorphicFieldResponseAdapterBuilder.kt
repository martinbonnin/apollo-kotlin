package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.ir.IrModel
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asTypeName

class MonomorphicFieldResponseAdapterBuilder(
    val context: JavaContext,
    val model: IrModel,
    val path: List<String>,
    val public: Boolean,
) : ResponseAdapterBuilder {

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

  override fun prepare() {
    context.resolver.registerModelAdapter(
        model.id,
        ClassName.from(path + adapterName)
    )
    nestedAdapterBuilders.map { it.prepare() }
  }

  override fun build(): List<TypeSpec> {
    return listOf(typeSpec())
  }

  private fun typeSpec(): TypeSpec {
    return TypeSpec.objectBuilder(adapterName)
        .addSuperinterface(
            JavaClassNames.Adapter.parameterizedBy(
                context.resolver.resolveModel(model.id)
            )
        )
        .applyIf(!public) {
          addModifiers(KModifier.PRIVATE)
        }
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
        .addAnnotation(JavaClassNames.Override)
        .addCode(readFromResponseCodeBlock(model, context, false))
        .build()
  }

  private fun writeToResponseMethodSpec(): MethodSpec {
    return MethodSpec.methodBuilder(Identifier.toJson)
        .addAnnotation(JavaClassNames.Override)
        .addParameter(Identifier.writer, JavaClassNames.JsonWriter)
        .addParameter(Identifier.customScalarAdapters, CustomScalarAdapters::class)
        .addParameter(Identifier.value, adaptedClassName)
        .addCode(writeToResponseCodeBlock(model, context))
        .build()
  }
}