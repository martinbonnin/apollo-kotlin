
package demo

import com.apollographql.apollo3.annotations.GraphQLDefault
import com.apollographql.apollo3.annotations.GraphQLQueryRoot
import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.execution.Coercing
import com.apollographql.apollo3.execution.internal.ExternalValue

@GraphQLQueryRoot
class Query {
  fun nullableWithoutDefault(arg: Optional<Int?>): String {
    return arg.toString()
  }

  fun nullableWithDefault(@GraphQLDefault("42") arg: Int?): String {
    return arg.toString()
  }

  fun requiredWithoutDefault(arg: Int): String {
    return arg.toString()
  }

  fun requiredWithDefault(@GraphQLDefault("42") arg: Int): String {
    return arg.toString()
  }

  fun nullableObjectWithoutDefault(arg: Optional<Input?>): String {
    return arg.toString()
  }

  fun nullableObjectWithDefault0(@GraphQLDefault("{c: 33}") arg: Input?): String {
    return arg.toString()
  }

  fun requiredObjectWithoutDefault(arg: Input): String {
    return arg.toString()
  }

  fun requiredObjectWithDefault1(@GraphQLDefault("42") arg: Input): String {
    return arg.toString()
  }

  fun whereNext(arg: Direction): Direction {
    return arg
  }
}

enum class Direction {
  SOUTH,
  NORTH
}

class Input(
    val a: Optional<Int?>,
    @GraphQLDefault("42") val b: Int?,
    val c: Int,
    @GraphQLDefault("42") val d: Int,
) {
  override fun toString(): String {
    return buildString {
      append("{")
      if (a is Optional.Present) {
        append("a: ${a.getOrThrow()}, ")
      }
      append("b: $b, c: $c, d: $d")
      append("}")
    }
  }
}
