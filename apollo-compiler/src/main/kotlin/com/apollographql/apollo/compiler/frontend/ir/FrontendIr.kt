package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLFragmentDefinition
import com.apollographql.apollo.compiler.frontend.GQLOperationDefinition
import com.apollographql.apollo.compiler.frontend.GQLTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLValue
import com.apollographql.apollo.compiler.toUpperCamelCase

/**
 * FrontendIr is computed from the GQLDocuments. Compared to the GQLDocument, it:
 * - resolves typeDefinitions and other things so that we don't have to carry over a schema in later steps
 * - same thing for allFragments
 * - interprets directives like @include, @skip and @deprecated
 * - merges fields into FieldSets
 *
 */
internal data class FrontendIr(
    val operations: List<Operation>,
    val fragmentDefinitions: List<NamedFragmentDefinition>,
    val allFragmentDefinitions: Map<String, NamedFragmentDefinition>
) {
  /**
   * @param shapes the different fieldSets that can map to a given operation. This is usually just one
   * but @include/@skip directives on query fragments can make this more complicated
   */
  data class Operation(
      val name: String,
      val operationType: FrontendIrBuilder.OperationType,
      val typeDefinition: GQLTypeDefinition,
      val variables: List<Variable>,
      val description: String?,
      val shapes: Shapes,
      val sourceWithFragments: String,
      val gqlOperationDefinition: GQLOperationDefinition
  )

  /**
   * A "shape" that will ultimately be converted to Kotlin
   *
   * @param implementedFragments: the fragments implemented by this shape
   * @param fieldSetConditions: the condition satisfied by this FieldSet.
   */
  data class FieldSet(
      val implementedFragments: List<String>,
      val fields: List<Field>,
      val fieldSetConditions: Set<FieldSetCondition>
  ) {
    /**
     * Outputs a name from a set of conditions
     */
    val name: String = fieldSetConditions.toList().sortedByDescending {
      it.vars.size
    }.map {
      it.vars.mapNotNull { it.takeIf { it.isType }?.name }.sorted().joinToString("") { it.toUpperCamelCase() } +
          it.vars.mapNotNull { it.takeIf { !it.isType }?.name }.sorted().joinToString("") { it.toUpperCamelCase() }
    }.joinToString("Or")

    override fun toString() = name
  }


  /**
   * A shorter version of [BooleanExpression]
   */
  data class FieldSetCondition(val vars: Set<Var>)

  data class Var(val name: String, val isType: Boolean)

  /**
   *
   */
  data class Shapes(val commonFields: List<Field>, val fieldSets: List<FieldSet>)

  /**
   * A Field
   *
   * @param type: the GraphQL type
   * @param canBeSkipped: whether this field is forced nullable because it has an @include/@skip directive
   * or it belongs to an inline fragment that has one.
   */
  data class Field(
      val alias: String?,
      val name: String,
      val booleanExpression: BooleanExpression,
      val type: Type,
      val arguments: List<Argument>,
      val description: String?,
      val deprecationReason: String?,
      val shapes: Shapes,
      val canBeSkipped: Boolean
  ) {
    val responseName = alias ?: name

    /**
     * Whether the type will be nullable in the final Kotlin model
     */
    val nullable = canBeSkipped || type !is Type.NonNull
  }

  data class NamedFragmentDefinition(
      val name: String,
      val description: String?,
      val shapes: Shapes,
      /**
       * Fragments do not have variables per-se but we can infer them from the document
       * Default values will always be null for those
       */
      val variables: List<Variable>,
      val typeCondition: GQLTypeDefinition,
      val source: String,
      val gqlFragmentDefinition: GQLFragmentDefinition
  )

  data class Variable(val name: String, val defaultValue: GQLValue?, val type: Type)

  /**
   * a [com.apollographql.apollo.compiler.frontend.GQLArgument] with additional defaultValue and resolved [Type].
   * value and defaultValue are coerced so that for an example, Ints used in Float positions are correctly transformed
   */
  data class Argument(
      val name: String,
      val defaultValue: GQLValue?,
      val value: GQLValue,
      val type: Type)

  /**
   * A [com.apollographql.apollo.compiler.frontend.GQLType] with a reference to its
   * [com.apollographql.apollo.compiler.frontend.GQLTypeDefinition] if needed so we don't have to look it up in the schema
   */
  sealed class Type {
    abstract val leafTypeDefinition: GQLTypeDefinition

    data class NonNull(val ofType: Type) : Type() {
      override val leafTypeDefinition = ofType.leafTypeDefinition
    }

    data class List(val ofType: Type) : Type() {
      override val leafTypeDefinition = ofType.leafTypeDefinition
    }

    class Named(val typeDefinition: GQLTypeDefinition) : Type() {
      override val leafTypeDefinition = typeDefinition

      override fun hashCode(): Int {
        return typeDefinition.name.hashCode()
      }

      override fun equals(other: Any?): Boolean {
        return (other as? Named)?.typeDefinition?.name == typeDefinition.name
      }
    }
  }
}