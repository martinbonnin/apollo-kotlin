package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.sir.SirExecutionContextArgument
import com.apollographql.apollo3.compiler.sir.SirGraphQLArgument
import com.apollographql.apollo3.compiler.sir.SirArgument
import com.apollographql.apollo3.compiler.sir.SirFieldDefinition
import com.apollographql.apollo3.compiler.sir.SirNonNullType
import com.apollographql.apollo3.compiler.sir.SirObjectDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode

internal fun resolverBody(sirObjectDefinition: SirObjectDefinition, sirTargetField: SirFieldDefinition): CodeBlock {
  val singleLine = sirTargetField.arguments.size < 2
  val nl = if (singleLine) "" else "\n"
  val sep = if (singleLine) ",·" else ",\n"

  return buildCode {
    indent {
      add("it.parentObject.cast<%T>().%L", sirObjectDefinition.targetClassName.asKotlinPoet(), sirTargetField.targetName)
      if (sirTargetField.isFunction) {
        add("($nl")
        indent(!singleLine) {
          add(sirTargetField.arguments.map { argumentCodeBlock(it) }.joinToCode(sep))
        }
        add(")")
      }
    }
  }
}

internal fun CodeBlock.Builder.indent(condition: Boolean = true, block: CodeBlock.Builder.() -> Unit) {
  if (condition) {
    indent()
  }
  block()
  if (condition) {
    unindent()
  }
}

private fun argumentCodeBlock(sirArgument: SirArgument): CodeBlock {
  return buildCode {
    when (sirArgument) {
      is SirGraphQLArgument -> {
        val getArgument = if (sirArgument.defaultValue == null && sirArgument.type !is SirNonNullType) {
          // No default value and nullable => Optional
          "getArgument"
        } else {
          "getRequiredArgument"
        }
        add(
            "%L·=·it.$getArgument(%S).cast()",
            sirArgument.targetName,
            sirArgument.name,
        )
      }

      SirExecutionContextArgument -> {
        add("executionContext·=·it.executionContext")
      }
    }
  }
}