package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.sir.SirExecutionContextArgument
import com.apollographql.apollo3.compiler.sir.SirGraphQLArgument
import com.apollographql.apollo3.compiler.sir.SirArgument
import com.apollographql.apollo3.compiler.sir.SirFieldDefinition
import com.apollographql.apollo3.compiler.sir.SirObjectDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode

internal fun resolverBody(sirObjectDefinition: SirObjectDefinition, sirTargetField: SirFieldDefinition): CodeBlock {
  val singleLine = sirTargetField.arguments.size < 2
  val nl = if(singleLine) "" else "\n"
  val sep = if(singleLine) ",·" else ",\n"

  return buildCode {
    indent{
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
private fun argumentCodeBlock(sirTargetArgument: SirArgument): CodeBlock {
  val builder = CodeBlock.builder()
  when (sirTargetArgument) {
    is SirGraphQLArgument -> {
//      /**
//       * Unwrap the optional because getArgument always returns an optional value
//       */
//      val type = if (sirTargetArgument.type.optional) {
//        sirTargetArgument.type.optional(false)
//      } else {
//        sirTargetArgument.type
//      }
//      builder.add(
//          "%L·=·it.getArgument<%T>(%S)",
//          sirTargetArgument.targetName,
//          resolver.resolveIrType(type = type, jsExport = false, isInterface = false),
//          sirTargetArgument.name,
//      )
//      if (!sirTargetArgument.type.optional) {
//        builder.add(".getOrThrow()")
//      }
    }

    SirExecutionContextArgument -> {
      builder.add("executionContext·=·it.executionContext")
    }
  }
  return builder.build()
}