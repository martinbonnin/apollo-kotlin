package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.rawValue
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T
import com.apollographql.apollo3.compiler.codegen.java.helpers.maybeAddDescription
import com.apollographql.apollo3.compiler.ir.IrEnum
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier

class EnumBuilder(
    private val context: JavaContext,
    private val enum: IrEnum,
) : JavaClassBuilder {
  private val layout = context.layout
  private val packageName = layout.typePackageName()
  private val simpleName = layout.enumName(name = enum.name)
  private val selfClassName = ClassName.get(packageName, simpleName)

  override fun prepare() {
    context.resolver.registerSchemaType(
        enum.name,
        selfClassName
    )
  }

  override fun build(): CodegenJavaFile {
    return CodegenJavaFile(
        packageName = packageName,
        typeSpec = enum.toSealedClassTypeSpec()
    )
  }

  private fun IrEnum.toSealedClassTypeSpec(): TypeSpec {
    return TypeSpec
        .classBuilder(simpleName)
        .maybeAddDescription(description)
        .addField(
            FieldSpec.builder(JavaClassNames.String, rawValue).build()
        )
        .addMethod(
            MethodSpec.constructorBuilder()
                .addParameter(ParameterSpec.builder(JavaClassNames.String, rawValue).build())
                .addCode("this.$rawValue = $rawValue")
                .build()
        )
        .addFields(
            values.map { value ->
              FieldSpec.builder(selfClassName, layout.enumName(name))
                  .initializer(CodeBlock.of("new $T($S)", selfClassName, name))
                  .build()
            }
        )
        .addType(
            unknownTypeSpec()
        )
        .build()
  }

  private fun unknownTypeSpec(): TypeSpec {
    return TypeSpec.classBuilder(Identifier.UNKNOWN__)
        .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
        .superclass(selfClassName)
        .addJavadoc("An enum value that wasn't known at compile time.\n")
        .addMethod(
            MethodSpec.constructorBuilder()
                .addParameter(ParameterSpec.builder(JavaClassNames.String, rawValue).build())
                .addCode("super($rawValue)")
                .build()
        )
        .build()
  }
}
