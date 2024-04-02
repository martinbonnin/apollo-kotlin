package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinResolver
import com.apollographql.apollo3.compiler.internal.applyIf
import com.apollographql.apollo3.compiler.sir.SirExecutionContextArgument
import com.apollographql.apollo3.compiler.sir.SirGraphQLArgument
import com.apollographql.apollo3.compiler.sir.SirArgument
import com.apollographql.apollo3.compiler.sir.SirFieldDefinition
import com.apollographql.apollo3.compiler.sir.SirObjectDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.apollographql.apollo3.compiler.ir.optional
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode

internal fun resolverBody(sirObjectDefinition: SirObjectDefinition, sirTargetField: SirFieldDefinition, resolver: KotlinResolver): CodeBlock {
  val singleLine = sirTargetField.arguments.size < 2

  return CodeBlock.Builder()
      .apply {
        if ((!singleLine)) {
          add("{\n")
          indent()
        } else {
          add("{·")
        }
      }
      .add("(it.parentObject·as·%T).%L", sirObjectDefinition.targetClassName.asKotlinPoet(), sirTargetField.targetName)
      .applyIf(sirTargetField.isFunction) {
        if (singleLine) {
          add("(")
          add(
              sirTargetField.arguments.map { irTargetArgument ->
                argumentCodeBlock(irTargetArgument, resolver)
              }.joinToCode(",·")
          )
          add(")")
        } else {
          add("(\n")
          indent()
          add(
              sirTargetField.arguments.map { irTargetArgument ->
                argumentCodeBlock(irTargetArgument, resolver)
              }.joinToCode(",\n", suffix = "\n")
          )
          unindent()
          add(")\n")
        }
      }
      .apply {
        if (!singleLine) {
          unindent()
          add("}")
        } else {
          add("·}")
        }
      }

      .build()
}

private fun argumentCodeBlock(sirTargetArgument: SirArgument, resolver: KotlinResolver): CodeBlock {
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