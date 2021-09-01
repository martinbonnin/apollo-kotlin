package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.T
import com.apollographql.apollo3.compiler.codegen.java.joinToCode
import com.squareup.javapoet.CodeBlock

fun List<CodeBlock>.toListInitializerCodeblock(): CodeBlock {
  return CodeBlock.builder()
      .add("$T.asList(", JavaClassNames.Arrays)
      .add(L, joinToCode(", "))
      .add(")")
      .build()
}

fun List<CodeBlock>.toArrayInitializerCodeblock(): CodeBlock {
  return CodeBlock.builder()
      .add("{")
      .add(L, joinToCode(", "))
      .add("}")
      .build()
}