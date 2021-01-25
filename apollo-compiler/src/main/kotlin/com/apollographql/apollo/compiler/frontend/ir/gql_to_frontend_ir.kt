package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLArgument
import com.apollographql.apollo.compiler.frontend.GQLBooleanValue
import com.apollographql.apollo.compiler.frontend.GQLDirective
import com.apollographql.apollo.compiler.frontend.GQLField
import com.apollographql.apollo.compiler.frontend.GQLFieldDefinition
import com.apollographql.apollo.compiler.frontend.GQLFragmentDefinition
import com.apollographql.apollo.compiler.frontend.GQLFragmentSpread
import com.apollographql.apollo.compiler.frontend.GQLInlineFragment
import com.apollographql.apollo.compiler.frontend.GQLListType
import com.apollographql.apollo.compiler.frontend.GQLNamedType
import com.apollographql.apollo.compiler.frontend.GQLNonNullType
import com.apollographql.apollo.compiler.frontend.GQLOperationDefinition
import com.apollographql.apollo.compiler.frontend.GQLSelectionSet
import com.apollographql.apollo.compiler.frontend.GQLType
import com.apollographql.apollo.compiler.frontend.GQLTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLVariableDefinition
import com.apollographql.apollo.compiler.frontend.GQLVariableValue
import com.apollographql.apollo.compiler.frontend.Schema
import com.apollographql.apollo.compiler.frontend.definitionFromScope
import com.apollographql.apollo.compiler.frontend.findDeprecationReason
import com.apollographql.apollo.compiler.frontend.leafType
import com.apollographql.apollo.compiler.frontend.possibleTypes
import com.apollographql.apollo.compiler.frontend.responseName
import com.apollographql.apollo.compiler.frontend.rootTypeDefinition
import com.apollographql.apollo.compiler.frontend.toUtf8
import com.apollographql.apollo.compiler.frontend.toUtf8WithIndents
import com.apollographql.apollo.compiler.frontend.usedFragmentNames
import com.apollographql.apollo.compiler.frontend.validateAndCoerce
import java.math.BigInteger

internal class FrontendIrBuilder(
    private val schema: Schema,
    private val operationDefinitions: List<GQLOperationDefinition>,
    metadataFragmentDefinitions: List<GQLFragmentDefinition>,
    fragmentDefinitions: List<GQLFragmentDefinition>
) {
  private val allGQLFragmentDefinitions = (metadataFragmentDefinitions + fragmentDefinitions).associateBy {
    it.name
  }

  private val irFragmentDefinitions = fragmentDefinitions.map {
    it.toIrNamedFragmentDefinition()
  }

  // For metadataFragments, we transform them to IR multiple times, in each module. This is a bit silly but
  // there's no real alternative as we still need the GQLFragmentDefinition to perform validation
  private val allFragmentDefinitions = (irFragmentDefinitions + metadataFragmentDefinitions.map {
    it.toIrNamedFragmentDefinition()
  }).associateBy { it.name }

  fun build(): FrontendIr {
    return FrontendIr(
        operations = operationDefinitions.map {
          it.toIrOperation()
        },
        fragmentDefinitions = irFragmentDefinitions,
        allFragmentDefinitions = allFragmentDefinitions
    )
  }

  private fun GQLOperationDefinition.toIrOperation(): FrontendIr.Operation {
    val typeDefinition = rootTypeDefinition(schema)
        ?: throw IllegalStateException("ApolloGraphql: cannot find root type for '$operationType'")

    val fragmentNames = usedFragmentNames(schema, allGQLFragmentDefinitions)

    return FrontendIr.Operation(
        name = name ?: throw IllegalStateException("Apollo doesn't support anonymous operation."),
        operationType = operationType.toIrOperationType(),
        variables = variableDefinitions.map { it.toIr() },
        typeDefinition = typeDefinition,
        fieldSets = listOf(selectionSet).collectFields(typeDefinition.name).toIRFieldSets(),
        description = description,
        sourceWithFragments = (toUtf8WithIndents() + "\n" + fragmentNames.joinToString(
            separator = "\n"
        ) { fragmentName ->
          allGQLFragmentDefinitions[fragmentName]!!.toUtf8WithIndents()
        }).trimEnd('\n'),
        gqlOperationDefinition = this
    )
  }

  private fun GQLFragmentDefinition.toIrNamedFragmentDefinition(): FrontendIr.NamedFragmentDefinition {
    val typeDefinition = schema.typeDefinition(typeCondition.name)
    return FrontendIr.NamedFragmentDefinition(
        name = name,
        description = description,
        fieldSets = listOf(selectionSet).collectFields(typeDefinition.name).toIRFieldSets(),
        typeCondition = typeDefinition,
        source = toUtf8WithIndents(),
        gqlFragmentDefinition = this,
        variables = selectionSet.inferredVariables(typeDefinition).map { FrontendIr.Variable(it.key, null, it.value.toIr()) }
    )
  }

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
      (argument.value as? GQLVariableValue)?.let { value ->
        val type = fieldDefinition.arguments.first { it.name == argument.name }.type
        argument.name to type
      }
    }?.toMap() ?: emptyMap()) + (selectionSet?.inferredVariables(schema.typeDefinition(fieldDefinition.type.leafType().name)) ?: emptyMap())
  }

  private fun GQLVariableDefinition.toIr(): FrontendIr.Variable {
    return FrontendIr.Variable(
        name = name,
        defaultValue = defaultValue?.validateAndCoerce(type, schema, null)?.orThrow(),
        type = type.toIr(),
    )
  }

  private fun GQLType.toIr(): FrontendIr.Type {
    return when (this) {
      is GQLNonNullType -> FrontendIr.Type.NonNull(ofType = type.toIr())
      is GQLListType -> FrontendIr.Type.List(ofType = type.toIr())
      is GQLNamedType -> FrontendIr.Type.Named(typeDefinition = schema.typeDefinition(name))
    }
  }

  /**
   * The result of collecting fields.
   *
   * @param baseType: the baseType of the object whose fields we will collect.
   * @param typeConditions: the type conditions used by the users in the query. Can contain [baseType]
   */
  data class CollectionResult(
      val baseType: String,
      val fields: List<CollectedField>,
      val fragments: List<CollectedFragment>,
      val typeConditions: Set<String>,
  )

  data class CollectedField(
      val gqlField: GQLField,
      /**
       * the parent type of this field
       */
      val parentTypeDefinition: GQLTypeDefinition,
      /**
       * the definition of this field
       */
      val fieldDefinition: GQLFieldDefinition,
      /**
       * Whether this field is nullable in the final model. This can happen if:
       * - the GraphQL type is nullable
       * - the field has a non-trivial @include/@skip directive
       * - the field belongs to an inline fragment with a non-trivial @include/@skip directive
       */
      val nullable: Boolean,
      /**
       * The real condition for this field to be included in the response.
       * This is used by the cache to not over fetch.
       */
      val condition: FrontendIr.Condition,
      /**
       * The condition for this field to be included in the model shape.
       * - for inline fragments, this only includes the type conditions as field can be made nullable later (cf [forceNullable])
       * - for named fragments, this also includes the @include/@skip directive on fragments
       * This is used by codegen to generate the models
       */
      val shapeCondition: FrontendIr.Condition,
  )

  data class CollectedFragment(
      val name: String,
      val condition: FrontendIr.Condition
  )

  /**
   * A helper class to collect fields in a given object/interface
   *
   */
  private class CollectionScope(
      val schema: Schema,
      val allGQLFragmentDefinitions: Map<String, GQLFragmentDefinition>,
      val gqlSelectionSet: GQLSelectionSet,
      val baseType: String
  ) {
    private val fields = mutableListOf<CollectedField>()
    private val fragments = mutableListOf<CollectedFragment>()
    private val typeConditions = mutableSetOf<String>()

    fun collect(): CollectionResult {
      check (fields.isEmpty()) {
        "CollectionScope can only be used once, please create a new CollectionScope"
      }
      gqlSelectionSet.collect(FrontendIr.Condition.True, FrontendIr.Condition.True, baseType, false)
      return CollectionResult(
          baseType = baseType,
          fields = fields,
          fragments = fragments,
          typeConditions = typeConditions,
      )
    }

    @Suppress("NAME_SHADOWING")
    private fun GQLSelectionSet.collect(condition: FrontendIr.Condition, shapeCondition: FrontendIr.Condition, parentType: String, forceNullable: Boolean) {
      var condition = condition.and(FrontendIr.Condition.Type(parentType))
      var shapeCondition = shapeCondition.and(FrontendIr.Condition.Type(parentType))

      typeConditions.add(parentType)

      selections.forEach {
        when (it) {
          is GQLField -> {
            val typeDefinition = schema.typeDefinition(parentType)
            val fieldDefinition = it.definitionFromScope(schema, typeDefinition)
                ?: error("cannot find definition for ${it.name} in $parentType")

            condition = condition.and(it.directives.toCondition())
            val nullable = forceNullable || fieldDefinition.type !is GQLNonNullType
            fields.add(
                CollectedField(
                    gqlField = it,
                    parentTypeDefinition = typeDefinition,
                    fieldDefinition = fieldDefinition,
                    nullable = nullable,
                    condition = condition,
                    shapeCondition = shapeCondition
                )
            )
          }
          is GQLInlineFragment -> {
            val directiveCondition = it.directives.toCondition()

            it.selectionSet.collect(
                condition.and(directiveCondition),
                shapeCondition,
                it.typeCondition.name,
                directiveCondition != FrontendIr.Condition.True
            )
          }
          is GQLFragmentSpread -> {
            val gqlFragmentDefinition = allGQLFragmentDefinitions[it.name]!!

            condition = condition.and(it.directives.toCondition())
            shapeCondition = shapeCondition.and(it.directives.toCondition())

            fragments.add(CollectedFragment(it.name, condition))

            gqlFragmentDefinition.selectionSet.collect(
                condition,
                shapeCondition,
                gqlFragmentDefinition.typeCondition.name,
                false
            )
          }
        }
      }
    }
  }

  /**
   * Given a list of selection sets sharing the same baseType, collect all fields and returns the CollectionResult
   */
  private fun List<GQLSelectionSet>.collectFields(baseType: String): CollectionResult {
    return map {
      CollectionScope(schema, allGQLFragmentDefinitions, it, baseType).collect()
    }.fold(CollectionResult(
        baseType,
        emptyList(),
        emptyList(),
        emptySet()
    )) { acc, item ->
      acc.copy(
          fields = acc.fields + item.fields,
          typeConditions = acc.typeConditions + item.typeConditions,
          fragments = acc.fragments + item.fragments,
      )
    }
  }

  /**
   * Transforms this [CollectionResult] to a list of [FrontendIr.FieldSet]. This is where the different shapes are created and deduped
   */
  private fun CollectionResult.toIRFieldSets(): List<FrontendIr.FieldSet> {
    if (fields.isEmpty()) {
      return emptyList()
    }

    val possibleTypes = typeConditions.map {
      it to schema.typeDefinition(it).possibleTypes(schema.typeDefinitions)
    }.toMap()

    /**
     * Optimization: for type conditions, we create an inverse mapping from each concrete type to
     * the type conditions it satisfies. We can limit the model generation to this partition.
     *
     * Example, with typeConditions [Being, Human, Wookie] and concrete types [Human, Wookie], we will
     * end up with the following inverse mapping
     *
     *  [Being, Human]: [Human],
     *  [Being, Wookie]: [Wookie]
     */
    val partition = schema.typeDefinition(baseType).possibleTypes(schema.typeDefinitions).map { concreteType ->
      typeConditions.filter { possibleTypes[it]!!.contains(concreteType) } to concreteType
    }.toMap()

    val possibleVariableValues = fragments.flatMap { it.condition.extractVariables() }
        .toSet()
        .possibleVariableValues()

    val fieldSets = partition.keys.flatMap { typeConditions ->
      possibleVariableValues.map { variables ->
        generateFieldSet(fields, typeConditions, variables, fragments)
      }
    }

    // TODO: dedup
    return fieldSets
  }

  private fun generateFieldSet(
      collectedFields: List<CollectedField>,
      typeConditions: List<String>,
      variables: Map<String, Boolean>,
      fragments: List<CollectedFragment>
  ): FrontendIr.FieldSet {
    val groupedFields = collectedFields
        .filter { it.shapeCondition.evaluate(variables, typeConditions.toSet()) }
        .groupBy { it.gqlField.responseName() }

    val fields = groupedFields.map { (responseName, fieldList) ->
      /**
       * All fields will have the same arguments and same type so for most things, we take the first one
       */
      val field = fieldList[0]

      val fieldLeafTypeDefinition = schema.typeDefinition(field.fieldDefinition.type.leafType().name)
      FrontendIr.Field(
          alias = field.gqlField.alias,
          name = field.gqlField.name,
          // If one field in the shape is satisfied then all of them are
          condition = FrontendIr.Condition.Or(fieldList.map { it.condition }.toSet()),
          type = field.fieldDefinition.type.toIr(),
          arguments = field.gqlField.arguments?.arguments?.map { it.toIrArgument(field.fieldDefinition) } ?: emptyList(),
          description = field.fieldDefinition.description,
          deprecationReason = field.fieldDefinition.directives.findDeprecationReason(),
          fieldSets = fieldList.mapNotNull {
            it.gqlField.selectionSet
          }.collectFields(fieldLeafTypeDefinition.name)
              .toIRFieldSets()
      )
    }

    val condition = FrontendIr.Condition.And(typeConditions.map { FrontendIr.Condition.Type(it) }.toSet())
        .and(*variables.filter { it.value }.keys.map { FrontendIr.Condition.Variable(it, false) }.toTypedArray())

    return FrontendIr.FieldSet(
        condition = condition,
        implementedFragments = fragments.filter { it.condition.evaluate(variables, typeConditions.toSet()) }.map { it.name } ,
        fields = fields
    )
  }



  private fun GQLArgument.toIrArgument(fieldDefinition: GQLFieldDefinition): FrontendIr.Argument {
    val inputValueDefinition = fieldDefinition.arguments.first { it.name == name }

    return FrontendIr.Argument(
        name = name,
        value = value.validateAndCoerce(inputValueDefinition.type, schema, null).orThrow(),
        defaultValue = inputValueDefinition.defaultValue?.validateAndCoerce(inputValueDefinition.type, schema, null)?.orThrow(),
        type = inputValueDefinition.type.toIr(),

        )
  }

  private fun String.toIrOperationType(): OperationType {
    return when (this) {
      "query" -> OperationType.Query
      "mutation" -> OperationType.Mutation
      "subscription" -> OperationType.Subscription
      else -> throw IllegalStateException("ApolloGraphQL: unknown operationType $this.")
    }
  }

  enum class OperationType {
    Query,
    Mutation,
    Subscription
  }

  companion object {
    private fun List<GQLDirective>.toCondition(): FrontendIr.Condition {
      val conditions = mapNotNull {
        it.toCondition()
      }
      return if (conditions.isEmpty()) {
        FrontendIr.Condition.True
      } else {
        check(conditions.toSet().size == conditions.size) {
          "ApolloGraphQL: duplicate @skip/@include directives are not allowed"
        }
        // Having both @skip and @include is allowed
        // 3.13.2 In the case that both the @skip and @include directives are provided on the same field or fragment,
        // it must be queried only if the @skip condition is false and the @include condition is true.
        FrontendIr.Condition.And(conditions.toSet())
      }
    }

    private fun GQLDirective.toCondition(): FrontendIr.Condition? {
      if (setOf("skip", "include").contains(name).not()) {
        // not a condition directive
        return null
      }
      if (arguments?.arguments?.size != 1) {
        throw IllegalStateException("ApolloGraphQL: wrong number of arguments for '$name' directive: ${arguments?.arguments?.size}")
      }

      val argument = arguments.arguments.first()

      return when (val value = argument.value) {
        is GQLBooleanValue -> {
          if (value.value) FrontendIr.Condition.True else FrontendIr.Condition.False
        }
        is GQLVariableValue -> FrontendIr.Condition.Variable(
            name = value.name,
            inverted = name == "skip"
        )
        else -> throw IllegalStateException("ApolloGraphQL: cannot pass ${value.toUtf8()} to '$name' directive")
      }
    }

    internal fun FrontendIr.Condition.extractVariables(): Set<String> = when (this) {
      is FrontendIr.Condition.Or -> conditions.flatMap { it.extractVariables() }.toSet()
      is FrontendIr.Condition.And -> conditions.flatMap { it.extractVariables() }.toSet()
      is FrontendIr.Condition.Variable -> setOf(this.name)
      else -> emptySet()
    }

    internal fun FrontendIr.Condition.extractTypes(): Set<String> = when (this) {
      is FrontendIr.Condition.Or -> conditions.flatMap { it.extractTypes() }.toSet()
      is FrontendIr.Condition.And -> conditions.flatMap { it.extractTypes() }.toSet()
      is FrontendIr.Condition.Type -> setOf(this.name)
      else -> emptySet()
    }

    /**
     * Given a set of variable names, generate all possible variable values
     */
    private fun Set<String>.possibleVariableValues(): List<Map<String, Boolean>> {
      val asList = toList()

      val list = mutableListOf<Map<String, Boolean>>()
      for (i in 0.until(BigInteger.valueOf(2).pow(size).toInt())) {
        val map = mutableMapOf<String, Boolean>()
        for (j in 0.until(size)) {
          map.put(asList[j], (i.and(1.shr(j)) != 0))
        }
        list.add(map)
      }

      return list
    }
  }
}
