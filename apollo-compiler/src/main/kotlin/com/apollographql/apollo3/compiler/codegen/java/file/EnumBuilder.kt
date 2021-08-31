package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.java.helpers.deprecatedAnnotation
import com.apollographql.apollo3.compiler.ir.IrEnum
import com.apollographql.apollo3.compiler.codegen.java.helpers.maybeAddDescription
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec

class EnumBuilder(
    private val context: JavaContext,
    private val enum: IrEnum
): JavaClassBuilder {
  private val layout = context.layout
  private val packageName = layout.typePackageName()
  private val simpleName = layout.enumName(name = enum.name)

  override fun prepare() {
    context.resolver.registerSchemaType(
        enum.name,
        ClassName.get(
            packageName,
            simpleName
        )
    )
  }


  override fun build(): CodegenJavaFile {
    return CodegenJavaFile(
        packageName = packageName,
        typeSpec = listOf(enum.toSealedClassTypeSpec())
    )
  }

  private fun IrEnum.toSealedClassTypeSpec(): TypeSpec {
    return TypeSpec
        .classBuilder(simpleName)
        .maybeAddDescription(description)
        // XXX: can an enum be made deprecated (and not only its values) ?
        .addModifiers(KModifier.SEALED)
        .primaryConstructor(primaryConstructorWithOverriddenParamSpec)
        .addField(rawValueFieldSpec)
        .addType(companionTypeSpec())
        .addTypes(values.map { value ->
          value.toObjectTypeSpec(ClassName("", layout.enumName(name)))
        })
        .addType(unknownValueTypeSpec())
        .build()
  }

  private fun IrEnum.companionTypeSpec(): TypeSpec {
    return TypeSpec.companionObjectBuilder()
        .addField(typeFieldSpec())
        .build()
  }

  private fun IrEnum.Value.toObjectTypeSpec(superClass: TypeName): TypeSpec {
    return TypeSpec.objectBuilder(layout.enumValueName(name))
        .applyIf(description?.isNotBlank() == true) { addKdoc("%L\n", description!!) }
        .applyIf(deprecationReason != null) { addAnnotation(deprecatedAnnotation(deprecationReason!!)) }
        .superclass(superClass)
        .addSuperclassConstructorParameter("rawValue = %S", name)
        .build()
  }

  private fun IrEnum.unknownValueTypeSpec(): TypeSpec {
    return TypeSpec.classBuilder("UNKNOWN__")
        .addKdoc("%L", "Auto generated constant for unknown enum values\n")
        .primaryConstructor(primaryConstructorSpec)
        .superclass(ClassName("", layout.enumName(name)))
        .addSuperclassConstructorParameter("rawValue = rawValue")
        .build()
  }

  fun className(): TypeName {
    return ClassName.get(
        packageName,
        simpleName
    )
  }

  private val primaryConstructorSpec =
      FunSpec
          .constructorBuilder()
          .addParameter("rawValue", String::class)
          .build()

  private val primaryConstructorWithOverriddenParamSpec =
      FunSpec
          .constructorBuilder()
          .addParameter("rawValue", String::class)
          .build()

  private val rawValueFieldSpec =
      FieldSpec
          .builder("rawValue", String::class)
          .initializer("rawValue")
          .build()

}
