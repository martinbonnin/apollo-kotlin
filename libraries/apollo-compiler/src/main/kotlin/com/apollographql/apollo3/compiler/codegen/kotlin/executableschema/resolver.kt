package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinResolver
import com.apollographql.apollo3.compiler.internal.applyIf
import com.apollographql.apollo3.compiler.ir.IrExecutionContextTargetArgument
import com.apollographql.apollo3.compiler.ir.IrGraphqlTargetArgument
import com.apollographql.apollo3.compiler.ir.IrTargetArgument
import com.apollographql.apollo3.compiler.ir.IrTargetField
import com.apollographql.apollo3.compiler.ir.IrObjectDefinition
import com.apollographql.apollo3.compiler.ir.asKotlinPoet
import com.apollographql.apollo3.compiler.ir.optional
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode

internal fun resolverBody(irObjectDefinition: IrObjectDefinition, irTargetField: IrTargetField, resolver: KotlinResolver): CodeBlock {
  val singleLine = irTargetField.arguments.size < 2

  return CodeBlock.Builder()
      .apply {
        if ((!singleLine)) {
          add("{\n")
          indent()
        } else {
          add("{·")
        }
      }
      .add("(it.parentObject·as·%T).%L", irObjectDefinition.targetClassName.asKotlinPoet(), irTargetField.targetName)
      .applyIf(irTargetField.isFunction) {
        if (singleLine) {
          add("(")
          add(
              irTargetField.arguments.map { irTargetArgument ->
                argumentCodeBlock(irTargetArgument, resolver)
              }.joinToCode(",·")
          )
          add(")")
        } else {
          add("(\n")
          indent()
          add(
              irTargetField.arguments.map { irTargetArgument ->
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

private fun argumentCodeBlock(irTargetArgument: IrTargetArgument, resolver: KotlinResolver): CodeBlock {
  val builder = CodeBlock.builder()
  when (irTargetArgument) {
    is IrGraphqlTargetArgument -> {
      /**
       * Unwrap the optional because getArgument always returns an optional value
       */
      val type = if (irTargetArgument.type.optional) {
        irTargetArgument.type.optional(false)
      } else {
        irTargetArgument.type
      }
      builder.add(
          "%L·=·it.getArgument<%T>(%S)",
          irTargetArgument.targetName,
          resolver.resolveIrType(type = type, jsExport = false, isInterface = false),
          irTargetArgument.name,
      )
      if (!irTargetArgument.type.optional) {
        builder.add(".getOrThrow()")
      }
    }

    IrExecutionContextTargetArgument -> {
      builder.add("executionContext·=·it.executionContext")
    }
  }
  return builder.build()
}