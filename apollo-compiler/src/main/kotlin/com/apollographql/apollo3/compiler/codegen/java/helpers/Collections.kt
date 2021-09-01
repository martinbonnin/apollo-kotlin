package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.T
import com.apollographql.apollo3.compiler.codegen.java.joinToCode
import com.squareup.javapoet.CodeBlock

fun List<CodeBlock>.toListCodeblock(): CodeBlock {
  return CodeBlock.builder()
      .add("$T.asList(", JavaClassNames.Arrays)
      .add(L, joinToCode(", "))
      .add(")")
      .build()
}