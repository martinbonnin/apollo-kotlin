package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLArgument
import com.apollographql.apollo.compiler.frontend.GQLBooleanValue
import com.apollographql.apollo.compiler.frontend.GQLEnumTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLEnumValue
import com.apollographql.apollo.compiler.frontend.GQLField
import com.apollographql.apollo.compiler.frontend.GQLFieldDefinition
import com.apollographql.apollo.compiler.frontend.GQLFloatValue
import com.apollographql.apollo.compiler.frontend.GQLFragmentDefinition
import com.apollographql.apollo.compiler.frontend.GQLFragmentSpread
import com.apollographql.apollo.compiler.frontend.GQLInlineFragment
import com.apollographql.apollo.compiler.frontend.GQLInputObjectTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLIntValue
import com.apollographql.apollo.compiler.frontend.GQLInterfaceTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLListType
import com.apollographql.apollo.compiler.frontend.GQLListValue
import com.apollographql.apollo.compiler.frontend.GQLNamedType
import com.apollographql.apollo.compiler.frontend.GQLNonNullType
import com.apollographql.apollo.compiler.frontend.GQLNullValue
import com.apollographql.apollo.compiler.frontend.GQLObjectTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLObjectValue
import com.apollographql.apollo.compiler.frontend.GQLOperationDefinition
import com.apollographql.apollo.compiler.frontend.GQLScalarTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLSelection
import com.apollographql.apollo.compiler.frontend.GQLSelectionSet
import com.apollographql.apollo.compiler.frontend.GQLStringValue
import com.apollographql.apollo.compiler.frontend.GQLType
import com.apollographql.apollo.compiler.frontend.GQLUnionTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLValue
import com.apollographql.apollo.compiler.frontend.GQLVariableDefinition
import com.apollographql.apollo.compiler.frontend.GQLVariableValue
import com.apollographql.apollo.compiler.frontend.Schema
import com.apollographql.apollo.compiler.frontend.definitionFromScope
import com.apollographql.apollo.compiler.frontend.findDeprecationReason
import com.apollographql.apollo.compiler.frontend.leafType
import com.apollographql.apollo.compiler.frontend.rootTypeDefinition
import com.apollographql.apollo.compiler.frontend.toBooleanExpression
import com.apollographql.apollo.compiler.frontend.toUtf8WithIndents
import com.apollographql.apollo.compiler.frontend.usedFragmentNames
import com.apollographql.apollo.compiler.frontend.validateAndCoerce

class FirBuilder(
    private val schema: Schema,
    private val operationDefinitions: List<GQLOperationDefinition>,
    private val metadataFragmentDefinitions: List<GQLFragmentDefinition>,
    private val fragmentDefinitions: List<GQLFragmentDefinition>
) {
  private val allGQLFragmentDefinitions = (metadataFragmentDefinitions + fragmentDefinitions).associateBy { it.name }

  internal fun build(): FIR {
    return FIR(
        operations = operationDefinitions.map { it.toFir() },
        allFragmentDefinitions = emptyMap(),
        fragmentDefinitions = emptyList()
    )
  }

  private fun firOperationType(operationType: String) = OperationType.valueOf(operationType.capitalize())

  private fun GQLOperationDefinition.toFir(): Operation {
    val typeDefinition = rootTypeDefinition(schema)
        ?: throw IllegalStateException("ApolloGraphql: cannot find root type for '$operationType'")

    val fragmentNames = usedFragmentNames(schema, allGQLFragmentDefinitions)

    return Operation(
        name = name ?: throw IllegalStateException("Apollo doesn't support anonymous operation."),
        operationType = firOperationType(operationType),
        typeCondition = typeDefinition.name,
        variables = variableDefinitions.map { it.toFir() },
        selectionSet = selectionSet.toFir(typeDefinition.name, BooleanExpression.True).let {
          FirMerger.mergeSelectionSet(it, typeDefinition.name)
        },
        description = description,
        sourceWithFragments = (toUtf8WithIndents() + "\n" + fragmentNames.joinToString(
            separator = "\n"
        ) { fragmentName ->
          allGQLFragmentDefinitions[fragmentName]!!.toUtf8WithIndents()
        }).trimEnd('\n'),
    )
  }

  private fun GQLVariableDefinition.toFir(): Variable {
    return Variable(
        name = name,
        defaultValue = defaultValue?.validateAndCoerce(type, schema, null)?.orThrow()?.toFir(),
        type = type.toFir(),
    )
  }

  private fun GQLType.toFir(): Type {
    return when (this) {
      is GQLNonNullType -> NonNullType(ofType = type.toFir())
      is GQLListType -> ListType(ofType = type.toFir())
      is GQLNamedType -> when (schema.typeDefinition(name)) {
        is GQLScalarTypeDefinition -> {
          when (name) {
            "String" -> StringType
            "Boolean" -> BooleanType
            "Int" -> IntType
            "Float" -> FloatType
            "ID" -> IDType
            else -> CustomScalarType(name)
          }
        }
        is GQLEnumTypeDefinition -> EnumType(name)
        is GQLObjectTypeDefinition -> ObjectType(name)
        is GQLInterfaceTypeDefinition -> InterfaceType(name)
        is GQLUnionTypeDefinition -> UnionType(name)
        is GQLInputObjectTypeDefinition -> InputObjectType(name)
      }
    }
  }

  private fun GQLValue.toFir(): Value {
    return when (this) {
      is GQLIntValue -> IntValue(value = value)
      is GQLStringValue -> StringValue(value = value)
      is GQLFloatValue -> FloatValue(value = value)
      is GQLBooleanValue -> BooleanValue(value = value)
      is GQLEnumValue -> EnumValue(value = value)
      is GQLNullValue -> NullValue
      is GQLVariableValue -> VariableValue(name = name)
      is GQLListValue -> ListValue(values = values.map { it.toFir() })
      is GQLObjectValue -> ObjectValue(fields = fields.map {
        ObjectValueField(name = it.name, value = it.value.toFir())
      })
    }
  }

  private fun GQLSelectionSet.toFir(parentType: String, parentCondition: BooleanExpression): SelectionSet {
    return SelectionSet(
        selections = selections.map { gqlSelection ->
          gqlSelection.toFir(parentType, parentCondition)
        }
    )
  }

  /**
   * Traverse the tree and applies field/inline fragments conditions to sub-fields
   */
  private fun GQLSelection.toFir(parentType: String, parentCondition: BooleanExpression): Selection {
    return when (this) {
      is GQLField -> {
        val fieldDefinition = definitionFromScope(schema, schema.typeDefinition(parentType))!!
        val selfCondition = parentCondition.and(directives.toBooleanExpression()).simplify()
        Field(
            name = name,
            alias = alias,
            description = fieldDefinition.description,
            deprecationReason = fieldDefinition.directives.findDeprecationReason(),
            arguments = arguments?.arguments?.map { it.toFir(fieldDefinition) } ?: emptyList(),
            condition = selfCondition,
            selectionSet = selectionSet?.toFir(fieldDefinition.type.leafType().name, selfCondition) ?: SelectionSet(emptyList()),
            type = fieldDefinition.type.toFir()
        )
      }
      is GQLInlineFragment -> {
        val selfCondition = parentCondition.and(directives.toBooleanExpression()).simplify()
        InlineFragment(
            typeCondition = typeCondition.name,
            selectionSet = selectionSet.toFir(typeCondition.name, selfCondition)
        )
      }
      is GQLFragmentSpread -> {
        val selfCondition = parentCondition.and(directives.toBooleanExpression()).simplify()
        FragmentSpread(
            name = name,
            condition = selfCondition
        )
      }
    }
  }

  private fun GQLArgument.toFir(fieldDefinition: GQLFieldDefinition): Argument {
    val inputValueDefinition = fieldDefinition.arguments.first { it.name == name }
    return Argument(
        name = name,
        value = value.toFir(),
        defaultValue = inputValueDefinition.defaultValue?.toFir(),
        type = inputValueDefinition.type.toFir()
    )
  }
}

