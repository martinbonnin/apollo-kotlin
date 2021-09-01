package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T
import com.apollographql.apollo3.compiler.ir.IrInputField
import com.apollographql.apollo3.compiler.ir.IrType
import com.apollographql.apollo3.compiler.ir.IrVariable
import com.apollographql.apollo3.compiler.ir.isOptional
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.ParameterSpec

class NamedType(
    val graphQlName: String,
    val description: String?,
    val deprecationReason: String?,
    val type: IrType,
)


internal fun NamedType.toParameterSpec(context: JavaContext): ParameterSpec {
  return ParameterSpec
      .builder(
          context.resolver.resolveIrType(type),
          context.layout.propertyName(graphQlName),
      )
      .build()
}


fun IrInputField.toNamedType() = NamedType(
    graphQlName = name,
    type = type,
    description = description,
    deprecationReason = deprecationReason,
)

fun IrVariable.toNamedType() = NamedType(
    graphQlName = name,
    type = type,
    description = null,
    deprecationReason = null,
)


internal fun List<NamedType>.writeToResponseCodeBlock(context: JavaContext): CodeBlock {
  val builder = CodeBlock.builder()
  forEach {
    builder.add(it.writeToResponseCodeBlock(context))
  }
  return builder.build()
}

internal fun NamedType.writeToResponseCodeBlock(context: JavaContext): CodeBlock {
  val adapterInitializer = context.resolver.adapterInitializer(type, false)
  val builder = CodeBlock.builder()
  val propertyName = context.layout.propertyName(graphQlName)

  if (type.isOptional()) {
    builder.beginControlFlow("if (${Identifier.value}.$propertyName is $T)", JavaClassNames.Present)
  }
  builder.addStatement("${Identifier.writer}.name($S)", graphQlName)
  builder.addStatement(
      "$L.${Identifier.toJson}(${Identifier.writer}, ${Identifier.customScalarAdapters}, ${Identifier.value}.$propertyName)",
      adapterInitializer
  )
  if (type.isOptional()) {
    builder.endControlFlow()
  }

  return builder.build()
}
