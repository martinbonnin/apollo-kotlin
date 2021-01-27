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
        shapes = listOf(selectionSet).collectFields(typeDefinition.name).toIRShapes(),
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
        shapes = listOf(selectionSet).collectFields(typeDefinition.name).toIRShapes(),
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
      if (argument.value is GQLVariableValue) {
        val type = fieldDefinition.arguments.first { it.name == argument.name }.type
        argument.name to type
      } else {
        null
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
   * @param typeConditions: the map of type conditions used by the users in the query to the list of variables that make their shape change.
   * typeConditions might contain [baseType] if a user explicitly mentioned it
   */
  data class CollectionResult(
      val baseType: String,
      val collectedFields: List<CollectedField>,
      val collectedNamedFragments: List<CollectedNamedFragment>,
      val collectedInlineFragments: List<CollectedInlineFragment>,
      val typeConditions: Map<String, Set<String>>,
  )

  data class CollectedField(
      val gqlField: GQLField,
      /**
       * the parent type of this field
       */
      val parentTypeDefinition: GQLTypeDefinition,
      /**
       * The definition of this field. This can also be computed from [gqlField] and [parentTypeDefinition]
       * It is added here for convenience
       */
      val fieldDefinition: GQLFieldDefinition,
      /**
       * Whether this field is nullable in the final model. This can happen if:
       * - the field has a non-trivial @include/@skip directive
       * - the field belongs to an inline fragment with a non-trivial @include/@skip directive
       */
      val canBeSkipped: Boolean,
      /**
       * The real condition for this field to be included in the response.
       * This is used by the cache to not over fetch.
       */
      val booleanExpression: BooleanExpression,
      /**
       * The condition for this field to be included in the model shape.
       * - for inline fragments, this only includes the type conditions as field can be made nullable later (cf [canBeSkipped])
       * - for named fragments, this also includes the @include/@skip directive on fragments
       * This is used by codegen to generate the models
       */
      val shapeBooleanExpression: BooleanExpression,
  )

  data class CollectedNamedFragment(
      val name: String,
      val booleanExpression: BooleanExpression
  )

  data class CollectedInlineFragment(
      // we identify inline fragments by their path, i.e. the typeconditions joined
      val path: String,
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
    private val collectedField = mutableListOf<CollectedField>()
    private val collectedNamedFragment = mutableListOf<CollectedNamedFragment>()
    private val collectedInlineFragment = mutableListOf<CollectedInlineFragment>()
    private val typeConditions = mutableMapOf<String, Set<String>>()

    fun collect(): CollectionResult {
      check(collectedField.isEmpty()) {
        "CollectionScope can only be used once, please create a new CollectionScope"
      }

      typeConditions[baseType] = emptySet()

      gqlSelectionSet.collect(BooleanExpression.True, BooleanExpression.True, baseType, false)
      return CollectionResult(
          baseType = baseType,
          collectedFields = collectedField,
          collectedNamedFragments = collectedNamedFragment,
          collectedInlineFragments = collectedInlineFragment,
          typeConditions = typeConditions,
      )
    }

    @Suppress("NAME_SHADOWING")
    private fun GQLSelectionSet.collect(condition: BooleanExpression, shapeCondition: BooleanExpression, parentType: String, canBeSkipped: Boolean) {
      val condition = condition.and(BooleanExpression.Type(parentType))
      val shapeCondition = shapeCondition.and(BooleanExpression.Type(parentType))

      selections.forEach {
        when (it) {
          is GQLField -> {
            val typeDefinition = schema.typeDefinition(parentType)
            val fieldDefinition = it.definitionFromScope(schema, typeDefinition)
                ?: error("cannot find definition for ${it.name} in $parentType")

            /**
             * This is where we decide whether we will force a field as nullable
             */
            val localCondition = it.directives.toCondition()
            val nullable = canBeSkipped || localCondition != BooleanExpression.True

            collectedField.add(
                CollectedField(
                    gqlField = it,
                    parentTypeDefinition = typeDefinition,
                    fieldDefinition = fieldDefinition,
                    canBeSkipped = nullable,
                    booleanExpression = condition.and(localCondition),
                    shapeBooleanExpression = shapeCondition
                )
            )
          }
          is GQLInlineFragment -> {
            val directiveCondition = it.directives.toCondition()

            typeConditions.putIfAbsent(it.typeCondition.name, emptySet())
            it.selectionSet.collect(
                condition.and(directiveCondition),
                shapeCondition,
                it.typeCondition.name,
                directiveCondition != BooleanExpression.True
            )
          }
          is GQLFragmentSpread -> {
            val gqlFragmentDefinition = allGQLFragmentDefinitions[it.name]!!

            val directivesCondition = it.directives.toCondition()

            val fragmentCondition = condition.and(directivesCondition)
            val fragmentShapeCondition = shapeCondition.and(directivesCondition)

            collectedNamedFragment.add(CollectedNamedFragment(it.name, fragmentCondition))

            typeConditions.merge(gqlFragmentDefinition.typeCondition.name, directivesCondition.extractVariables()) { oldValue, newValue ->
              oldValue.union(newValue)
            }

            gqlFragmentDefinition.selectionSet.collect(
                fragmentCondition,
                fragmentShapeCondition,
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
        emptyList(),
        emptyMap()
    )) { acc, item ->
      acc.copy(
          collectedFields = acc.collectedFields + item.collectedFields,
          typeConditions = acc.typeConditions + item.typeConditions,
          collectedNamedFragments = acc.collectedNamedFragments + item.collectedNamedFragments,
      )
    }
  }

  /**
   * Transforms this [CollectionResult] to a list of [FrontendIr.FieldSet]. This is where the different shapes are created and deduped
   */
  private fun CollectionResult.toIRShapes(): FrontendIr.Shapes {
    if (collectedFields.isEmpty()) {
      return FrontendIr.Shapes(emptyList(), emptyList())
    }

    val possibleTypes = typeConditions.keys.map {
      it to schema.typeDefinition(it).possibleTypes(schema.typeDefinitions)
    }.toMap()

    /**
     * Optimization: for type conditions, we create an inverse mapping from each concrete type to
     * the type conditions it satisfies. We can limit the model generation to this partition.
     *
     * Example, with typeConditions \[Being, Human, Wookie\] and concrete types \[Human, Wookie\], we will
     * end up with the following inverse mapping
     *
     *  \[Being, Human\]: \[Human\],
     *  \[Being, Wookie\]: \[Wookie\]
     */
    val partition = schema.typeDefinition(baseType).possibleTypes(schema.typeDefinitions).map { concreteType ->
      typeConditions.keys.filter { possibleTypes[it]!!.contains(concreteType) }.toSet() to concreteType
    }.toMap()

    /**
     * Generate a FieldSet for each possible combination of user-defined conditions + one of the baseType
     */
    val typeConditionSets: Set<Set<String>> = partition.keys.union(setOf(setOf(baseType)))


    val fieldSets = typeConditionSets.flatMap { tcs ->
      val possibleVariableValues = typeConditions.filter {
        tcs.contains(it.key)
      }.values.flatten()
          .toSet()
          .possibleVariableValues()
      possibleVariableValues.map { variables ->
        generateFieldSet(
            collectedFields = collectedFields,
            typeConditions = tcs,
            variables = variables,
            fragments = collectedNamedFragments
        )
      }
    }

    /**
     *  Dedup the identical FieldSet. FieldSets are considered identical if they have the same shape.
     *
     *  Duplicated shapes can happen in the following cases:
     *  1. Different typeConditions end up querying the same fields
     *  2. Disjoint types query fields with the same shape (see https://spec.graphql.org/draft/#SameResponseShape())
     *  3. Variables do not affect the shape of the response
     *
     *  If there's no @include/@skip directive, the order of the fields should be predictable and deduplicating shapes means we're
     *  losing it.
     *  If we ever want to do optimize more, we can remove this step (deduplicating the baseTypeCondition might still be useful)
     */
    val remainingFieldSets = fieldSets.toMutableList()
    val dedupedFieldSets = mutableListOf<FrontendIr.FieldSet>()

    while (remainingFieldSets.isNotEmpty()) {
      val currentFieldSet = remainingFieldSets.removeAt(0)
      val index = dedupedFieldSets.indexOfFirst { it.isIdenticalTo(currentFieldSet) }
      if (index == -1) {
        dedupedFieldSets.add(currentFieldSet)
      } else {
        /**
         * two [FrontendIr.FieldSet] have the same shape. Keep only one
         *
         * - merge the conditions
         * - merge the implementedFragments
         */
        val existingFieldSet = dedupedFieldSets[index]
        dedupedFieldSets.removeAt(index)
        dedupedFieldSets.add(
            existingFieldSet.copy(
                fieldSetConditions = existingFieldSet.fieldSetConditions + currentFieldSet.fieldSetConditions,
                implementedFragments = existingFieldSet.implementedFragments + currentFieldSet.implementedFragments
            )
        )
      }
    }

    val commonResponseNames = dedupedFieldSets.map { it.fields.map { it.responseName } }.intersection().toSet()
    val commonFields = dedupedFieldSets.flatMap { it.fields }
        .associateBy { it.responseName }
        .filterValues { commonResponseNames.contains(it.responseName) }
        .values
        .toList()

    return FrontendIr.Shapes(
        commonFields = commonFields,
        fieldSets = dedupedFieldSets.map {
          it.copy(fieldSetConditions = it.fieldSetConditions.toList().simplify().toSet())
        }
    )
  }

  private fun List<List<String>>.intersection(): List<String> {
    if (isEmpty()) {
      return emptyList()
    }

    return fold(get(0)) { acc, list ->
      acc.intersect(list).toList()
    }
  }
  /**
   * A very naive Karnaugh map simplification
   * A more advanced version could be https://en.wikipedia.org/wiki/Quine%E2%80%93McCluskey_algorithm
   */
  private fun List<FrontendIr.FieldSetCondition>.simplify(): List<FrontendIr.FieldSetCondition> {
    val list = this

    for (i in list.indices) {
      for (j in (i + 1).until(list.size)) {
        val fieldSetCondition1 = list[i]
        val fieldSetCondition2 = list[j]

        val merge = fieldSetCondition1.mergeWith(fieldSetCondition2)
        if (merge != null) {
          return (list.filterIndexed { index, _ -> index != i && index != j } + merge).simplify()
        }
      }
    }
    return this
  }

  private fun FrontendIr.FieldSetCondition.mergeWith(other: FrontendIr.FieldSetCondition): FrontendIr.FieldSetCondition? {
    val union = this.vars.union(other.vars)
    val intersection = this.vars.intersect(other.vars)

    if (union.subtract(intersection).size <= 1) {
      // 0 means they're the same condition
      // 1 means they can be combined: ( A & !B | A & B = A)
      return FrontendIr.FieldSetCondition(intersection)
    }
    return null
  }

  /**
   * This could ultimately be an `equals` call but there are a lot of small details so I prefer to keep it apart for now
   */
  private fun FrontendIr.FieldSet.isIdenticalTo(other: FrontendIr.FieldSet): Boolean {
    return fields.areIdenticalTo(other.fields)
  }
  private fun List<FrontendIr.Field>.areIdenticalTo(others: List<FrontendIr.Field>): Boolean {
    val thisFields = this.associateBy { it.responseName }
    val otherFields = others.associateBy { it.responseName }
    if (thisFields.size != otherFields.size) {
      return false
    }

    thisFields.keys.forEach {
      val thisField = thisFields[it]!!
      val otherField = otherFields[it] ?: return false

      if (thisField.shapes.fieldSets.isEmpty()) {
        /**
         * Scalar or enum types
         *
         * They are equals if their GraphQL types are equals
         */
        if (otherField.shapes.fieldSets.isNotEmpty()) {
          return false
        }

        if (thisField.nullable != otherField.nullable) {
          return false
        }

        // since the nullability can be overridden, we skip that in the actual check
        val thisType = if (thisField.type is FrontendIr.Type.NonNull) thisField.type.ofType else thisField.type
        val otherType = if (otherField.type is FrontendIr.Type.NonNull) otherField.type.ofType else thisField.type

        /**
         * [FrontendIr.Type] implements equals so that should work
         */
        if (thisType != otherType) {
          return false
        }
      } else {
        /**
         * Object, interface, union
         *
         * They are equals if their common fields shapes are equals
         */
        if (!thisField.shapes.commonFields.areIdenticalTo(otherField.shapes.commonFields)) {
          return false
        }
      }
    }
    return true
  }

  private fun generateFieldSet(
      collectedFields: List<CollectedField>,
      typeConditions: Set<String>,
      variables: Set<String>,
      fragments: List<CollectedNamedFragment>,
  ): FrontendIr.FieldSet {
    val groupedFields = collectedFields
        .filter { it.shapeBooleanExpression.evaluate(variables, typeConditions) }
        .groupBy { it.gqlField.responseName() }

    val fields = groupedFields.map { (_, fieldList) ->
      /**
       * All fields will have the same arguments and same type so for most things, we take the first one
       */
      val field = fieldList[0]

      val fieldLeafTypeDefinition = schema.typeDefinition(field.fieldDefinition.type.leafType().name)
      FrontendIr.Field(
          alias = field.gqlField.alias,
          name = field.gqlField.name,
          // if all the merged fields are skippable, the resulting one is too but if one of them is not, we know we will have something
          canBeSkipped = fieldList.all { it.canBeSkipped },
          // If one field in the shape is satisfied then all of them are
          booleanExpression = BooleanExpression.Or(fieldList.map { it.booleanExpression }.toSet()),
          type = field.fieldDefinition.type.toIr(),
          arguments = field.gqlField.arguments?.arguments?.map { it.toIrArgument(field.fieldDefinition) } ?: emptyList(),
          description = field.fieldDefinition.description,
          deprecationReason = field.fieldDefinition.directives.findDeprecationReason(),
          shapes = fieldList.mapNotNull {
            it.gqlField.selectionSet
          }.collectFields(fieldLeafTypeDefinition.name)
              .toIRShapes()
      )
    }

    return FrontendIr.FieldSet(
        fieldSetConditions = setOf(
            FrontendIr.FieldSetCondition(
                (typeConditions.map { FrontendIr.Var(name = it, isType = true) } + variables.map { FrontendIr.Var(name = it, isType = true) }).toSet()
            )
        ),
        implementedFragments = fragments.filter { it.booleanExpression.evaluate(variables, typeConditions) }.map { it.name },
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
    private fun List<GQLDirective>.toCondition(): BooleanExpression {
      val conditions = mapNotNull {
        it.toCondition()
      }
      return if (conditions.isEmpty()) {
        BooleanExpression.True
      } else {
        check(conditions.toSet().size == conditions.size) {
          "ApolloGraphQL: duplicate @skip/@include directives are not allowed"
        }
        // Having both @skip and @include is allowed
        // 3.13.2 In the case that both the @skip and @include directives are provided on the same field or fragment,
        // it must be queried only if the @skip condition is false and the @include condition is true.
        BooleanExpression.And(conditions.toSet())
      }
    }

    private fun GQLDirective.toCondition(): BooleanExpression? {
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
          if (value.value) BooleanExpression.True else BooleanExpression.False
        }
        is GQLVariableValue -> BooleanExpression.Variable(
            name = value.name,
        ).let {
          if (name == "skip") it.not() else it
        }
        else -> throw IllegalStateException("ApolloGraphQL: cannot pass ${value.toUtf8()} to '$name' directive")
      }
    }

    internal fun BooleanExpression.extractVariables(): Set<String> = when (this) {
      is BooleanExpression.Or -> booleanExpressions.flatMap { it.extractVariables() }.toSet()
      is BooleanExpression.And -> booleanExpressions.flatMap { it.extractVariables() }.toSet()
      is BooleanExpression.Variable -> setOf(this.name)
      else -> emptySet()
    }

    /**
     * Given a set of variable names, generate all possible variable values
     */
    private fun Set<String>.possibleVariableValues(): List<Set<String>> {
      val asList = toList()

      val pow = BigInteger.valueOf(2).pow(size).toInt()
      val list = mutableListOf<Set<String>>()
      for (i in 0.until(pow)) {
        val set = mutableSetOf<String>()
        for (j in 0.until(size)) {
          if (i.and(1.shl(j)) != 0) {
            set.add(asList[j])
          }
        }
        list.add(set)
      }

      return list
    }
  }
}
