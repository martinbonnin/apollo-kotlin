package com.apollographql.apollo3.execution.internal

import com.apollographql.apollo3.api.json.MapJsonReader
import com.apollographql.apollo3.ast.GQLEnumTypeDefinition
import com.apollographql.apollo3.ast.GQLEnumValue
import com.apollographql.apollo3.ast.GQLField
import com.apollographql.apollo3.ast.GQLInputObjectTypeDefinition
import com.apollographql.apollo3.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo3.ast.GQLListType
import com.apollographql.apollo3.ast.GQLListValue
import com.apollographql.apollo3.ast.GQLNamedType
import com.apollographql.apollo3.ast.GQLNonNullType
import com.apollographql.apollo3.ast.GQLNullValue
import com.apollographql.apollo3.ast.GQLObjectTypeDefinition
import com.apollographql.apollo3.ast.GQLObjectValue
import com.apollographql.apollo3.ast.GQLScalarTypeDefinition
import com.apollographql.apollo3.ast.GQLType
import com.apollographql.apollo3.ast.GQLUnionTypeDefinition
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.ast.GQLVariableValue
import com.apollographql.apollo3.ast.Schema
import com.apollographql.apollo3.ast.definitionFromScope
import com.apollographql.apollo3.execution.Coercing

internal fun coerceArgumentValues(schema: Schema, typename: String, field: GQLField, coercings: Map<String, Coercing>, coercedVariables: Map<String, InternalValue>): Map<String, InternalValue> {
  val coercedValues = mutableMapOf<String, InternalValue>()
  val argumentValues = field.arguments.associate { it.name to it.value }
  val argumentDefinitions = field.definitionFromScope(schema, typename)!!.arguments

  argumentDefinitions.forEach { argumentDefinition ->
    val argumentName = argumentDefinition.name
    val argumentType = argumentDefinition.type
    val defaultValue = argumentDefinition.defaultValue
    var hasValue = argumentValues.containsKey(argumentName)
    val argumentValue = argumentValues.get(argumentName)
    // This may hold either an InternalValue (for coerced variables) or a GQLValue (for argument values)
    var value: Any?
    if (argumentValue is GQLVariableValue) {
      val variableName = argumentValue.name
      hasValue = coercedVariables.containsKey(variableName)
      value = coercedVariables.get(variableName)
    } else {
      value = argumentValue
    }
    if (!hasValue && defaultValue != null) {
      /**
       * Sadly defaultValues are not coerced 😞
       * This is conceptually wrong but also what the spec is saying so this is what we want I guess
       * See https://github.com/graphql/graphql-spec/pull/793
       */
      coercedValues.put(argumentName, defaultValue.toInternalValue())
      return@forEach
    }
    if (argumentType is GQLNonNullType) {
      if (!hasValue) {
        error("No value passed for required argument: '$argumentName'")
      }
      if (value == null) {
        error("'null' found in non-null position for argument: '$argumentName'")
      }
    }

    if (hasValue) {
      if (value == null) {
        coercedValues.put(argumentName, null)
      } else if (argumentValue is GQLVariableValue) {
        coercedValues.put(argumentName, value)
      } else if (value is GQLValue) {
        coercedValues.put(argumentName, coerceLitteralToInternal(schema, value, argumentType, coercings, coercedVariables))
      } else {
        error("Cannot coerce '$value'")
      }

    }
  }

  return coercedValues
}

/**
 *
 */
internal fun coerceLitteralToInternal(schema: Schema, value: GQLValue, type: GQLType, coercings: Map<String, Coercing>, coercedVariables: Map<String, InternalValue>): InternalValue {
  if (value is GQLNullValue) {
    check(type !is GQLNonNullType) {
      error("'null' found in non-null position")
    }
  }
  if (value is GQLVariableValue) {
    /**
     * The absence of a variable MUST be checked at the callsite
     *
     * This is done for:
     * - top level argument values
     * - input objects
     * - lists
     */
    return coercedVariables.get(value.name)
  }

  return when (type) {
    is GQLNonNullType -> {
      coerceLitteralToInternal(schema, value, type.type, coercings, coercedVariables)
    }

    is GQLListType -> {
      if (value is GQLListValue) {
        value.values.map {
          if (it is GQLVariableValue) {
            if (coercedVariables.containsKey(it.name)) {
              coercedVariables.get(it.name)
            } else {
              // In lists, absent variables are coerced to null
              null
            }
          } else {
            coerceLitteralToInternal(schema, it, type.type, coercings, coercedVariables)
          }
        }
      } else {
        if (value is GQLVariableValue) {
          if (coercedVariables.containsKey(value.name)) {
            coercedVariables.get(value.name)
          } else {
            null
          }
        } else {
          // Single items are mapped to a list of 1
          listOf(coerceLitteralToInternal(schema, value, type.type, coercings, coercedVariables))
        }
      }
    }

    is GQLNamedType -> {
      val definition = schema.typeDefinition(type.name)
      when (definition) {
        is GQLEnumTypeDefinition -> {
          if (value is GQLEnumValue) {
            check(definition.enumValues.any { it.name == value.value }) {
              val possibleValues = definition.enumValues.map { it.name }.toSet()
              "'$value' cannot be coerced to a '${definition.name}' enum value. Possible values are: '$possibleValues'"
            }
            value.value
          } else {
            error("Don't know how to coerce '$value' to a '${definition.name}' enum value")
          }
        }

        is GQLInputObjectTypeDefinition -> {
          coerceInputObject(schema, definition, value, coercings, coercedVariables)
        }

        is GQLInterfaceTypeDefinition,
        is GQLObjectTypeDefinition,
        is GQLUnionTypeDefinition,
        -> {
          error("Output type '${definition.name}' cannot be used in input position")
        }

        is GQLScalarTypeDefinition -> {
          val coercing = coercings.get(definition.name)
          check(coercing != null) {
            "No coercing found for scalar type '${definition.name}'"
          }
          coercing.parseLiteral(value)
        }
      }
    }
  }
}

private fun coerceInputObject(schema: Schema, definition: GQLInputObjectTypeDefinition, literalValue: GQLValue, coercings: Map<String, Coercing>, coercedVariables: Map<String, InternalValue>): InternalValue {
  if (literalValue !is GQLObjectValue) {
    error("Don't know how to coerce '$literalValue' to a '${definition.name}' input object")
  }

  val fields = literalValue.fields.associate { it.name to it.value }

  return definition.inputFields.mapNotNull { inputValueDefinition ->
    val inputFieldType = inputValueDefinition.type

    if (!fields.containsKey(inputValueDefinition.name)) {
      if (inputValueDefinition.defaultValue != null) {
        inputValueDefinition.defaultValue!!.toInternalValue()
      } else {
        if (inputFieldType is GQLNonNullType) {
          error("Missing input field '${inputValueDefinition.name}")
        }
        // Skip this field
        null
      }
    } else {
      val inputFieldValue = fields.get(inputValueDefinition.name)!!
      inputValueDefinition.name to coerceLitteralToInternal(schema, inputFieldValue, inputValueDefinition.type, coercings, coercedVariables)
    }
  }
}