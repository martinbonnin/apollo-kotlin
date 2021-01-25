package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLFragmentDefinition
import com.apollographql.apollo.compiler.frontend.GQLOperationDefinition
import com.apollographql.apollo.compiler.frontend.GQLTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLValue

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
  data class Operation(
      val name: String,
      val operationType: FrontendIrBuilder.OperationType,
      val typeDefinition: GQLTypeDefinition,
      val variables: List<Variable>,
      val description: String?,
      val fieldSet: FieldSet,
      val sourceWithFragments: String,
      val gqlOperationDefinition: GQLOperationDefinition
  )

  /**
   * A "shape" that will ultimately be converted to Kotlin
   *
   * @param isBase: true when this is the "base" shape, i.e. this corresponds directly to the type of field without any fragment
   * @param implementedFragments: the fragments implemented by this shape
   * @param condition: the condition satisfied by this FieldSet. Initially, this is a set of typeConditions and variables combined
   * with 'and'. But if FieldSets are merged, this can be a more complex condition including 'or'
   */
  data class FieldSet(
      val isBase: Boolean,
      val implementedFragments: List<String>,
      val fields: List<Field>,
      val condition: Condition,
  )

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
      val condition: Condition,
      val type: Type,
      val arguments: List<Argument>,
      val description: String?,
      val deprecationReason: String?,
      val fieldSets: List<FieldSet>,
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
      val fieldSet: FieldSet,
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
   * A condition.
   * It initially comes from @include/@skip directives but is extended to account for variables, type conditions and any combination
   */
  sealed class Condition {
    abstract fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>): Boolean
    abstract fun simplify(): Condition

    fun or(other: Condition) = Or(setOf(this, other)).simplify()
    fun and(vararg other: Condition) = And((other.toList() + this).toSet()).simplify()

    object True : Condition() {
      override fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>) = true
      override fun simplify() = this
    }

    object False : Condition() {
      override fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>) = false
      override fun simplify() = this
    }

    data class Or(val conditions: Set<Condition>) : Condition() {
      init {
        check(conditions.isNotEmpty()) {
          "ApolloGraphQL: cannot create a 'Or' condition from an empty list"
        }
      }

      override fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>) =
          conditions.firstOrNull { it.evaluate(variables, typeConditions) } != null

      override fun simplify() = conditions.filter {
        it != False
      }.map { it.simplify() }
          .let {
            when {
              it.contains(True) -> True
              it.isEmpty() -> False
              it.size == 1 -> it.first()
              else -> {
                Or(it.toSet())
              }
            }
          }
    }

    data class And(val conditions: Set<Condition>) : Condition() {
      init {
        check(conditions.isNotEmpty()) {
          "ApolloGraphQL: cannot create a 'And' condition from an empty list"
        }
      }

      override fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>) =
          conditions.firstOrNull { !it.evaluate(variables, typeConditions) } == null

      override fun simplify() = conditions.filter {
        it != True
      }.map { it.simplify() }
          .let {
            when {
              it.contains(False) -> False
              it.isEmpty() -> True
              it.size == 1 -> it.first()
              else -> {
                And(it.toSet())
              }
            }
          }
    }


    data class Variable(
        val name: String,
        val inverted: Boolean,
    ) : Condition() {
      override fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>) = variables.get(name)?.let {
        if (inverted) {
          it.not()
        } else {
          it
        }
      } ?: throw IllegalStateException("ApolloGraphQL: Unknown variable: $name in ${variables.keys}")

      override fun simplify() = this
    }

    data class Type(
        val name: String,
    ) : Condition() {
      override fun evaluate(variables: Map<String, Boolean>, typeConditions: Set<String>) = typeConditions.contains(name)

      override fun simplify() = this
    }
  }

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