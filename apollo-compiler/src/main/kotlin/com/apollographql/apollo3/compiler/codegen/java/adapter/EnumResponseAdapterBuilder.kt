package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.reader
import com.apollographql.apollo3.compiler.codegen.Identifier.toJson
import com.apollographql.apollo3.compiler.codegen.Identifier.value
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.joinToCode
import com.apollographql.apollo3.compiler.ir.IrEnum
import com.squareup.javapoet.ClassName
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec

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
        typeSpec = enum.typeSpec()
    )
  }

  private fun IrEnum.typeSpec(): TypeSpec {
    val adaptedTypeName = context.resolver.resolveSchemaType(enum.name)
    val fromResponseMethodSpec = MethodSpec.methodBuilder(Identifier.fromJson)
        .addAnnotation(JavaClassNames.Override)
        .addParameter(JavaClassNames.JsonReader, reader)
        .addParameter(JavaClassNames.CustomScalarAdapters, customScalarAdapters)
        .returns(adaptedTypeName)
        .addCode(
            CodeBlock.builder()
                .addStatement("val rawValue = reader.nextString()!!")
                .beginControlFlow("return when(rawValue)")
                .add(
                    values
                        .map { CodeBlock.of("$S -> $L.$L", it.name, layout.enumName(name), layout.enumValueName(it.name)) }
                        .joinToCode(separator = "\n", suffix = "\n")
                )
                .add("else -> $L.UNKNOWN__$L\n", layout.enumName(name), "(rawValue)")
                .endControlFlow()
                .build()
        )
        .addAnnotation(JavaClassNames.Override)
        .build()
    val toResponseMethodSpec = toResponseMethodSpecBuilder(adaptedTypeName)
        .addCode("$writer.$value($value.rawValue)")
        .build()

    return TypeSpec.classBuilder(layout.enumResponseAdapterName(name))
        .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.Adapter, adaptedTypeName))
        .addMethod(fromResponseMethodSpec)
        .addMethod(toResponseMethodSpec)
        .build()
  }
}

internal fun toResponseMethodSpecBuilder(typeName: TypeName) = MethodSpec.methodBuilder(toJson)
    .addAnnotation(JavaClassNames.Override)
    .addParameter(JavaClassNames.JsonWriter, writer)
    .addParameter(JavaClassNames.CustomScalarAdapters, customScalarAdapters)
    .addParameter(typeName, value)