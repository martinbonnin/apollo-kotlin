package com.apollographql.apollo.compiler.frontend

internal fun inferVariables(
    gqlSelectionSet: GQLSelectionSet,
    typeDefinitionInScope: GQLTypeDefinition,
    schema: Schema,
    allGQLFragmentDefinitions: Map<String, GQLFragmentDefinition>
) = InferVariablesScope(schema, allGQLFragmentDefinitions).infer(gqlSelectionSet, typeDefinitionInScope)


internal class InferVariablesScope(val schema: Schema, val allGQLFragmentDefinitions: Map<String, GQLFragmentDefinition>) {
  private fun GQLSelectionSet.inferredVariables(typeDefinitionInScope: GQLTypeDefinition): Map<String, GQLType> {
    return selections.fold(emptyMap()) { acc, selection ->
      acc + when (selection) {
        is GQLField -> selection.inferredVariables(typeDefinitionInScope)
        is GQLInlineFragment -> selection.inferredVariables()
        is GQLFragmentSpread -> selection.inferredVariables()
      }
    }
  }

  private fun GQLInlineFragment.inferredVariables() = selectionSet.inferredVariables(schema.typeDefinition(typeCondition.name))

  private fun GQLFragmentSpread.inferredVariables(): Map<String, GQLType> {
    val fragmentDefinition = allGQLFragmentDefinitions[name]!!

    return fragmentDefinition.selectionSet.inferredVariables(schema.typeDefinition(fragmentDefinition.typeCondition.name))
  }

  private fun GQLField.inferredVariables(typeDefinitionInScope: GQLTypeDefinition): Map<String, GQLType> {
    val fieldDefinition = definitionFromScope(schema, typeDefinitionInScope)!!

    return (arguments?.arguments?.mapNotNull { argument ->
      if (argument.value is GQLVariableValue) {
        val type = fieldDefinition.arguments.first { it.name == argument.name }.type
        argument.name to type
      } else {
        null
      }
    }?.toMap() ?: emptyMap()) + (selectionSet?.inferredVariables(schema.typeDefinition(fieldDefinition.type.leafType().name)) ?: emptyMap())
  }

  fun infer(gqlSelectionSet: GQLSelectionSet, typeDefinitionInScope: GQLTypeDefinition): Map<String, GQLType> {
    return gqlSelectionSet.inferredVariables(typeDefinitionInScope)
  }
}
