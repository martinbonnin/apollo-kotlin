package com.apollographql.apollo.compiler.frontend.ir

import com.squareup.kotlinpoet.TypeSpec

fun SelectionSet.toImplementationTypeSpecs(parentType: String, responseName: String): List<TypeSpec> {
  val typeConditions = selections.flatMap { it.collectTypeConditions() }.toSet()


}

private fun Selection.collectTypeConditions(): List<String> {
  return when(this) {
    is Field -> selectionSet.selections.flatMap { it.collectTypeConditions() }
    is InlineFragment -> listOf(typeCondition) + selectionSet.selections.flatMap { it.collectTypeConditions() }
    is FragmentSpread -> listOf(typeCondition) + selectionSet.selections.flatMap { it.collectTypeConditions() }
  }
}