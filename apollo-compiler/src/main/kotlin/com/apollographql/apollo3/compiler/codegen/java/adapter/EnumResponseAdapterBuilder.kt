package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.reader
import com.apollographql.apollo3.compiler.codegen.Identifier.toJson
import com.apollographql.apollo3.compiler.codegen.Identifier.value
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.ir.IrEnum
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asClassName
import com.squareup.javapoet.asTypeName
import com.squareup.javapoet.joinToCode

class EnumResponseAdapterBuilder(
    val context: JavaContext,
    val enum: IrEnum,
) : JavaClassBuilder {
  private val layout = context.layout
  private val packageName = layout.typeAdapterPackageName()
  private val simpleName = layout.enumResponseAdapterName(enum.name)

  override fun prepare() {
    context.resolver.registerEnumAdapter(
        enum.name,
        ClassName.get(packageName, simpleName)
    )
  }

  override fun build(): CodegenJavaFile {
    return CodegenJavaFile(
        packageName = packageName,
        fileName = simpleName,
        typeSpec = enum.typeSpec()
    )
  }

  private fun IrEnum.typeSpec(): TypeSpec {
    val adaptedTypeName = context.resolver.resolveSchemaType(enum.name)
    val fromResponseMethodSpec = MethodSpec.methodBuilder(Identifier.fromJson)
        .addAnnotation(JavaClassNames.Override)
        .addParameter(reader, JsonReader::class)
        .addParameter(customScalarAdapters, CustomScalarAdapters::class)
        .returns(adaptedTypeName)
        .addCode(
            CodeBlock.builder()
                .addStatement("val rawValue = reader.nextString()!!")
                .beginControlFlow("return when(rawValue)")
                .add(
                    values
                        .map { CodeBlock.of("%S -> %L.%L", it.name, layout.enumName(name), layout.enumValueName(it.name)) }
                        .joinToCode(separator = "\n", suffix = "\n")
                )
                .add("else -> %L.UNKNOWN__%L\n", layout.enumName(name), "(rawValue)")
                .endControlFlow()
                .build()
        )
        .addAnnotation(JavaClassNames.Override)
        .build()
    val toResponseMethodSpec = toResponseMethodSpecBuilder(adaptedTypeName)
        .addCode("${Identifier.writer}.${Identifier.value}(${Identifier.value}.rawValue)")
        .build()

    return TypeSpec
        .objectBuilder(layout.enumResponseAdapterName(name))
        JavaClassNames..addSuperinterface(Adapter.parameterizedBy(adaptedTypeName))
        .addMethod(fromResponseMethodSpec)
        .addMethod(toResponseMethodSpec)
        .build()
  }
}

internal fun toResponseMethodSpecBuilder(typeName: TypeName) = MethodSpec.methodBuilder(toJson)
    .addAnnotation(JavaClassNames.Override)
    .addParameter(name = writer, type = JavaClassNames.JsonWriter)
    .addParameter(name = customScalarAdapters, type = CustomScalarAdapters::class)
    .addParameter(value, typeName)