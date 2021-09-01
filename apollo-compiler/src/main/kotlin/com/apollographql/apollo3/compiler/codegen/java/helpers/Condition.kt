package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.apollographql.apollo3.api.BPossibleTypes
import com.apollographql.apollo3.api.BTerm
import com.apollographql.apollo3.api.BVariable
import com.apollographql.apollo3.api.BooleanExpression
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.joinToCode
import com.squareup.javapoet.CodeBlock

internal fun BooleanExpression<BTerm>.codeBlock(): CodeBlock {
  return when(this) {
    is BooleanExpression.False -> CodeBlock.of("new %T.INSTANCE", JavaClassNames.False)
    is BooleanExpression.True -> CodeBlock.of("new %T.INSTANCE", JavaClassNames.True)
    is BooleanExpression.And -> {
      val parameters = operands.map {
        it.codeBlock()
      }.joinToCode(",")

      CodeBlock.of(
          "new %T(%L)",
          JavaClassNames.And,
          parameters
      )
    }
    is BooleanExpression.Or -> {
      val parameters = operands.map {
        it.codeBlock()
      }.joinToCode(",")
      CodeBlock.of(
          "new %T(%L)",
          JavaClassNames.Or,
          parameters
      )
    }
    is BooleanExpression.Not -> CodeBlock.of(
        "new %T(%L)",
        JavaClassNames.Not,
        operand.codeBlock()
    )
    is BooleanExpression.Element -> {
      when(val v = value) {
        is BVariable -> {
          CodeBlock.of(
              "new %T(%S)",
              JavaClassNames.BVariable,
              v.name
          )
        }
        is BPossibleTypes -> {
          CodeBlock.of(
              "new %T(%L)",
              JavaClassNames.BPossibleTypes,
              v.possibleTypes.map { CodeBlock.of("%S", it) }.joinToCode(",")
          )
        }
        else -> error("")
      }
    }
    else -> error("")
  }
}