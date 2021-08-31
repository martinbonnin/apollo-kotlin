package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FunSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.TypeSpec

/**
 * Makes this [TypeSpec.Builder] a data class and add a primary constructor using the given parameter spec
 * as well as the corresponding properties
 */
fun TypeSpec.Builder.makeDataClass(parameters: List<ParameterSpec>) = apply {
  if (parameters.isNotEmpty()) {
    addModifiers(KModifier.DATA)
  }
  primaryConstructor(FunSpec.constructorBuilder()
      .apply {
        parameters.forEach {
          addParameter(it)
        }
      }
      .build())
  parameters.forEach {
    addField(FieldSpec.builder(it.name, it.type)
        .initializer(CodeBlock.of(it.name))
        .build())
  }
}

fun TypeSpec.Builder.makeDataClassFromProperties(properties: List<FieldSpec>) = apply {
  if (properties.isNotEmpty()) {
    addModifiers(KModifier.DATA)
  }
  primaryConstructor(FunSpec.constructorBuilder()
      .apply {
        properties.forEach {
          addParameter(it.name, it.type)
        }
      }
      .build())

  properties.forEach {
    addField(it.toBuilder(it.name)
        .initializer(CodeBlock.of(it.name))
        .build()
    )
  }
}
