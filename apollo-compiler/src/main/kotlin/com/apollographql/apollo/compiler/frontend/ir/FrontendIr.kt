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
 * - infers fragment variables
 * - merges fields into FieldSets and Shapes
 *
 */
internal data class FrontendIr(
    val operations: List<Operation>,
    val fragmentDefinitions: List<NamedFragmentDefinition>,
    val allFragmentDefinitions: Map<String, NamedFragmentDefinition>
) {
  /**
   * @param shapes the different fieldSets that can map to a given operation. This contains usually just one FieldSet
   * but @include/@skip directives on root operation types can make this more complicated
   */
  data class Operation(
      val name: String,
      val operationType: FrontendIrBuilder.OperationType,
      val typeDefinition: GQLTypeDefinition,
      val variables: List<Variable>,
      val description: String?,
      val dataField: IField,
      val sourceWithFragments: String,
      val gqlOperationDefinition: GQLOperationDefinition
  )

  private fun Set<FieldSetCondition>.toName(): String {
    return toList().sortedByDescending {
      it.vars.size
    }.map {
      it.vars.mapNotNull { it.takeIf { it.isType }?.name }.sorted().joinToString("") { it.toUpperCamelCase() } +
          it.vars.mapNotNull { it.takeIf { !it.isType }?.name }.sorted().joinToString("") { it.toUpperCamelCase() }
    }.joinToString("Or")
  }

  /**
   * A shorter version of [BooleanExpression]
   */
  data class FieldSetCondition(val vars: Set<Var>)

  data class Var(val name: String, val isType: Boolean)

  data class Interfaces(
      val name: String,
      )

  /**
   * used for codegen
   */
  data class FieldInfo(
      val description: String?,
      val deprecationReason: String?,
      val responseName: String,
      val type: Type,
      val mightBeSkipped: Boolean
  )

  data class IField(
      val info: FieldInfo,
      val inode: INode?
  )

  // Represents a list of fields and associated condition
  // This is the building block of different interfaces
  // It can correspond to
  // - subfields of a fields
  // - named and inline fragments
  data class INode(
      val typeCondition: String,
      val ifields: List<IField>,
      val children: List<INode>,
  )

  data class DField(
      val fieldInfo: FieldInfo,
      /**
       * used for cache
       */
      val name: String,
      val alias: String?,
      val arguments: List<Argument>,
      val condition: BooleanExpression,
      /**
       * can be null for scalar types
       */
      //val inode: INode?,
  )

  data class Implementation(
      val fieldSetConditions: Set<FieldSetCondition>,
      val fields: List<DField>,
  )

  data class NamedFragmentDefinition(
      val name: String,
      val description: String?,
      val dataField: IField,
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