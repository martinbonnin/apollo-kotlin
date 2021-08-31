package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.__typename
import com.apollographql.apollo3.compiler.codegen.Identifier.fromJson
import com.apollographql.apollo3.compiler.codegen.Identifier.reader
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.toJson
import com.apollographql.apollo3.compiler.codegen.Identifier.value
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.ir.IrModelGroup
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asTypeName

class PolymorphicFieldResponseAdapterBuilder(
    val context: JavaContext,
    val modelGroup: IrModelGroup,
    val path: List<String>,
    val public: Boolean,
) : ResponseAdapterBuilder {
  private val baseModel = modelGroup.models.first {
    it.id == modelGroup.baseModelId
  }
  private val adapterName = baseModel.modelName
  private val adaptedClassName by lazy {
    context.resolver.resolveModel(baseModel.id)
  }

  private val implementations = modelGroup
      .models
      .filter { !it.isInterface }

  private val implementationAdapterBuilders = implementations.map {
    ImplementationAdapterBuilder(
        context,
        it,
        path
    )
  }

  override fun prepare() {
    context.resolver.registerModelAdapter(
        modelGroup.baseModelId,
        ClassName.get(
            path.first(),
            path.drop(1) + adapterName
        )
    )
    implementationAdapterBuilders.forEach {
      it.prepare()
    }
  }

  override fun build(): List<TypeSpec> {
    return listOf(typeSpec()) + implementationAdapterBuilders.map { it.build() }
  }

  private fun typeSpec(): TypeSpec {
    return TypeSpec.objectBuilder(adapterName)
        .addSuperinterface(
            JavaClassNames.Adapter.parameterizedBy(adaptedClassName)
        )
        .applyIf(!public) {
          addModifiers(KModifier.PRIVATE)
        }
        .addField(responseNamesFieldSpec())
        .addMethod(readFromResponseMethodSpec())
        .addMethod(writeToResponseMethodSpec())
        .build()
  }

  private fun responseNamesFieldSpec(): FieldSpec {
    return FieldSpec.builder(Identifier.RESPONSE_NAMES, List::class.parameterizedBy(String::class))
        .initializer("listOf(%S)", "__typename")
        .build()
  }

  private fun readFromResponseMethodSpec(): MethodSpec {
    return MethodSpec.methodBuilder(fromJson)
        .returns(adaptedClassName)
        .addParameter(reader, JsonReader::class)
        .addParameter(customScalarAdapters, CustomScalarAdapters::class)
        .addAnnotation(JavaClassNames.Override)
        .addCode(readFromResponseCodeBlock())
        .build()
  }

  private fun readFromResponseCodeBlock(): CodeBlock {
    val builder = CodeBlock.builder()

    builder.beginControlFlow("$reader.selectName(${Identifier.RESPONSE_NAMES}).also {")
    builder.beginControlFlow("check(it == 0) {")
    builder.addStatement("%S", "__typename not present in first position")
    builder.endControlFlow()
    builder.endControlFlow()
    builder.addStatement("val $__typename = reader.nextString()!!")

    builder.beginControlFlow("return when($__typename) {")
    implementations.sortedByDescending { it.typeSet.size }.forEach { model ->
      if (!model.isFallback) {
        model.possibleTypes.forEach { possibleType ->
          builder.addStatement("%S,", possibleType)
        }
      } else {
        builder.addStatement("else")
      }
      builder.addStatement(
          "-> %T.$fromJson($reader, $customScalarAdapters, $__typename)",
          ClassName.from(path + model.modelName),
      )
    }
    builder.endControlFlow()

    return builder.build()
  }

  private fun writeToResponseMethodSpec(): MethodSpec {
    return MethodSpec.methodBuilder(toJson)
        .addAnnotation(JavaClassNames.Override)
        .addParameter(writer, JavaClassNames.JsonWriter)
        .addParameter(customScalarAdapters, CustomScalarAdapters::class)
        .addParameter(value, adaptedClassName)
        .addCode(writeToResponseCodeBlock())
        .build()
  }

  private fun writeToResponseCodeBlock(): CodeBlock {
    val builder = CodeBlock.builder()

    builder.beginControlFlow("when($value) {")
    implementations.sortedByDescending { it.typeSet.size }.forEach { model ->
      builder.addStatement(
          "is %T -> %T.$toJson($writer, $customScalarAdapters, $value)",
          context.resolver.resolveModel(model.id),
          ClassName.from(path + model.modelName),
      )
    }
    builder.endControlFlow()

    return builder.build()
  }
}