@file:Suppress("UNCHECKED_CAST")

import com.apollographql.apollo3.api.ExecutionContext
import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.execution.GraphQLRequest
import com.apollographql.apollo3.execution.GraphQLResponse
import demo.DemoExecutableSchemaBuilder
import demo.Direction
import demo.Input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArgumentTest {
  private val executableSchema = DemoExecutableSchemaBuilder().build()

  @Test
  fun scalarArgsTest() {
    assertArg("nullableWithoutDefault", Optional.absent(), "Absent")
    assertArg("nullableWithoutDefault", Optional.present(null), "Present(null)")
    assertArg("nullableWithoutDefault", Optional.present(33), "Present(33)")
    assertArg("nullableWithoutDefault", Optional.present(Variable("Int", Optional.absent())), "Absent")
    assertArg("nullableWithoutDefault", Optional.present(Variable("Int", Optional.present(null))), "Present(null)")
    assertArg("nullableWithoutDefault", Optional.present(Variable("Int", Optional.present(33))), "Present(33)")

    assertArg("nullableWithDefault", Optional.absent(), "42")
    assertArg("nullableWithDefault", Optional.present(null), "null")
    assertArg("nullableWithDefault", Optional.present(33), "33")
    assertArg("nullableWithDefault", Optional.present(Variable("Int", Optional.absent())), "42")
    assertArg("nullableWithDefault", Optional.present(Variable("Int", Optional.present(null))), "null")
    assertArg("nullableWithDefault", Optional.present(Variable("Int", Optional.present(33))), "33")

    assertFails("requiredWithoutDefault", Optional.absent(), "No value passed for required argument 'arg'")
    assertFails("requiredWithoutDefault", Optional.present(null), "Value `null` cannot be used in position expecting `Int!`")
    assertArg("requiredWithoutDefault", Optional.present(33), "33")
    assertFails("requiredWithoutDefault", Optional.present(Variable("Int!", Optional.absent())), "No variable found for 'mmyvar'")
    assertFails("requiredWithoutDefault", Optional.present(Variable("Int!", Optional.present(null))), "'null' is not accepted for 'mmyvar'")
    assertArg("requiredWithoutDefault", Optional.present(Variable("Int!", Optional.present(33))), "33")

    assertArg("requiredWithDefault", Optional.absent(), "42")
    assertFails("requiredWithDefault", Optional.present(null), "Value `null` cannot be used in position expecting `Int!`")
    assertArg("requiredWithDefault", Optional.present(33), "33")
    assertFails("requiredWithDefault", Optional.present(Variable("Int!", Optional.absent())), "No variable found for 'mmyvar'")
    assertFails("requiredWithDefault", Optional.present(Variable("Int!", Optional.present(null))), "'null' is not accepted for 'mmyvar'")
    assertArg("requiredWithDefault", Optional.present(Variable("Int!", Optional.present(33))), "33")
  }

  @Test
  fun enumTest() {
    assertArg("whereNext", Optional.present(Direction.NORTH), "NORTH")
  }

  @Test
  fun inputTest() {
    assertArg("requiredObjectWithoutDefault", Optional.present("{c: 3}"), "{b: 42, c: 3, d: 42}")
  }

  class Variable(val type: String, val value: Optional<Any?>) {
    val name = "mmyvar"
    override fun toString(): String {
      return "\$$name"
    }
  }

  private fun assertFails(field: String, arg: Optional<Any?>, expectedMessage: String) {
    assertResponse(field, arg) {
      assertEquals(expectedMessage, errors?.first()?.message)
    }
  }

  private fun assertArg(field: String, arg: Optional<Any?>, expectedResult: String) {
    assertResponse(field, arg) {
      assertNull(errors)
      assertEquals(expectedResult, (data as Map<String, Any>).get(field))
    }
  }

  private fun assertResponse(field: String, arg: Optional<Any?>, block: GraphQLResponse.() -> Unit) {
    val document = buildString {
      (arg.getOrNull() as? Variable)?.let {
        append("query MyQuery($it: ${it.type})")
      }
      append('{')
      append(field)
      if (arg is Optional.Present) {
        /**
         * Relies on the fact that Any.toString() is the same as the expected GraphQL coercion
         */
        append("(arg: ${arg.getOrThrow()})")
      }
      append('}')
    }
    executableSchema.execute(
        GraphQLRequest.Builder()
            .document(document)
            .apply {
              (arg.getOrNull() as? Variable)?.let {
                if (it.value is Optional.Present) {
                  variables(mapOf(it.name to it.value.getOrThrow()))
                }
              }
            }
            .build(),
        ExecutionContext.Empty
    ).block()
  }
}

