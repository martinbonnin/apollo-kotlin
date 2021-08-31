package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.api.CustomScalarType
import com.apollographql.apollo3.api.EnumType
import com.apollographql.apollo3.api.InterfaceType
import com.apollographql.apollo3.api.ObjectType
import com.apollographql.apollo3.api.UnionType
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.type
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaResolver
import com.apollographql.apollo3.compiler.codegen.java.helpers.maybeAddDeprecation
import com.apollographql.apollo3.compiler.codegen.java.helpers.maybeAddDescription
import com.apollographql.apollo3.compiler.codegen.java.joinToCode
import com.apollographql.apollo3.compiler.ir.IrCustomScalar
import com.apollographql.apollo3.compiler.ir.IrEnum
import com.apollographql.apollo3.compiler.ir.IrInterface
import com.apollographql.apollo3.compiler.ir.IrObject
import com.apollographql.apollo3.compiler.ir.IrUnion
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.asTypeName
import com.squareup.javapoet.joinToCode
import javax.lang.model.element.Modifier

internal fun IrCustomScalar.typeFieldSpec(): FieldSpec {
  /**
   * Custom Scalars without a mapping will generate code using [AnyResponseAdapter] directly
   * so the fallback isn't really required here. We still write it as a way to hint the user
   * to what's happening behind the scenes
   */
  val kotlinName = kotlinName ?: "kotlin.Any"
  return FieldSpec
      .builder(JavaClassNames.CustomScalarType, Identifier.type, Modifier.STATIC)
      .initializer("%T(%S, %S)", JavaClassNames.CustomScalarType, name, kotlinName)
      .build()
}

internal fun IrEnum.typeFieldSpec(): FieldSpec {
  return FieldSpec
      .builder(JavaClassNames.EnumType, Identifier.type, Modifier.STATIC)
      .initializer("%T(%S)", JavaClassNames.EnumType, name)
      .build()
}

private fun Set<String>.toCode(): CodeBlock {
  val builder = CodeBlock.builder()
  builder.add("listOf(")
  builder.add("%L", sorted().map { CodeBlock.of("%S", it) }.joinToCode(", "))
  builder.add(")")
  return builder.build()
}

private fun List<String>.implementsToCode(resolver: JavaResolver): CodeBlock {
  val builder = CodeBlock.builder()
  builder.add("listOf(")
  builder.add("%L", sorted().map {
    resolver.resolveCompiledType(it)
  }.joinToCode(", "))
  builder.add(")")
  return builder.build()
}

internal fun IrObject.typeFieldSpec(resolver: JavaResolver): FieldSpec {
  val builder = CodeBlock.builder()
  builder.add("%T(name = %S", ObjectType::class.asTypeName(), name)
  if (keyFields.isNotEmpty()) {
    builder.add(", ")
    builder.add("keyFields = %L", keyFields.toCode())
  }
  if (implements.isNotEmpty()) {
    builder.add(", ")
    builder.add("implements = %L", implements.implementsToCode(resolver))
  }
  builder.add(")")

  return FieldSpec
      .builder(type, ObjectType::class)
      .initializer(builder.build())
      .build()
}

internal fun IrInterface.typeFieldSpec(resolver: JavaResolver): FieldSpec {
  val builder = CodeBlock.builder()
  builder.add("%T(name = %S", InterfaceType::class.asTypeName(), name)
  if (keyFields.isNotEmpty()) {
    builder.add(", ")
    builder.add("keyFields = %L", keyFields.toCode())
  }
  if (implements.isNotEmpty()) {
    builder.add(", ")
    builder.add("implements = %L", implements.implementsToCode(resolver))
  }
  builder.add(")")

  return FieldSpec
      .builder(type, InterfaceType::class)
      .initializer(builder.build())
      .build()
}


internal fun IrUnion.typeFieldSpec(resolver: JavaResolver): FieldSpec {
  val builder = CodeBlock.builder()
  builder.add(members.map {
    resolver.resolveCompiledType(it)
  }.joinToCode(", "))

  return FieldSpec
      .builder(type, UnionType::class)
      .maybeAddDescription(description)
      .maybeAddDeprecation(deprecationReason)
      .initializer("%T(%S, %L)", UnionType::class.asTypeName(), name, builder.build())
      .build()
}