package com.apollographql.apollo.compiler.frontend.ir

/**
 * A condition.
 * It initially comes from @include/@skip directives but is extended to account for variables, type conditions and any combination
 */
sealed class Condition {
  abstract fun evaluate(variables: Set<String>, typeConditions: Set<String>): Boolean
  abstract fun simplify(): Condition

  fun or(vararg other: Condition) = Or((other.toList() + this).toSet())
  fun and(vararg other: Condition) = And((other.toList() + this).toSet())
  fun not() = Not(this)

  object True : Condition() {
    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) = true
    override fun simplify() = this
    override fun toString() = "true"
  }

  object False : Condition() {
    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) = false
    override fun simplify() = this
    override fun toString() = "false"
  }

  data class Not(val condition: Condition): Condition() {
    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) = !condition.evaluate(variables, typeConditions)
    override fun simplify() = this
    override fun toString() = "!$condition"
  }

  data class Or(val conditions: Set<Condition>) : Condition() {
    init {
      check(conditions.isNotEmpty()) {
        "ApolloGraphQL: cannot create a 'Or' condition from an empty list"
      }
    }

    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) =
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

    override fun toString() = conditions.joinToString(" | ")
  }

  data class And(val conditions: Set<Condition>) : Condition() {
    init {
      check(conditions.isNotEmpty()) {
        "ApolloGraphQL: cannot create a 'And' condition from an empty list"
      }
    }

    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) =
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
    override fun toString() = conditions.joinToString(" & ")
  }


  data class Variable(
      val name: String,
  ) : Condition() {
    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) = variables.contains(name)
    override fun simplify() = this
    override fun toString() = "Var($name)"
  }

  data class Type(
      val name: String,
  ) : Condition() {
    override fun evaluate(variables: Set<String>, typeConditions: Set<String>) = typeConditions.contains(name)

    override fun simplify() = this

    override fun toString() = "Type($name)"
  }
}