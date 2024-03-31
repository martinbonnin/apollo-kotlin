package com.apollographql.apollo3.compiler.ir

import com.apollographql.apollo3.annotations.ApolloInternal
import com.squareup.kotlinpoet.ClassName

@ApolloInternal
data class IrClassName(
    val packageName: String,
    val names: List<String>
) {
  fun asString(): String {
    return "$packageName.${names.joinToString(".")}"
  }
}

internal fun IrClassName.asKotlinPoet(): ClassName = ClassName(packageName, names)

@ApolloInternal
class IrTargetField(
    val name: String,
    val targetName: String,
    val isFunction: Boolean,
    val type: IrType,
    val arguments: List<IrTargetArgument>
)

@ApolloInternal
sealed interface IrTargetArgument

@ApolloInternal
object IrExecutionContextTargetArgument: IrTargetArgument

@ApolloInternal
class IrGraphqlTargetArgument(
    val name: String,
    val targetName: String,
    val type: IrType,
): IrTargetArgument

@ApolloInternal
sealed interface IrTypeDefinition

@ApolloInternal
class IrScalarDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String,
): IrTypeDefinition

@ApolloInternal
class IrObjectDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String,
    val targetClassName: IrClassName,
    val isSingleton: Boolean,
    val hasNoArgsConstructor: Boolean,
    /**
     * If this is a root type, what root it is for
     */
    val operationType: String?,
    val fields: List<IrTargetField>,
): IrTypeDefinition

@ApolloInternal
class IrInterfaceDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String,
    val possibleTypes: List<String>,
    val fields: List<IrTargetField>,
): IrTypeDefinition

@ApolloInternal
class IrUnionDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String,
    val possibleTypes: List<String>,
): IrTypeDefinition

@ApolloInternal
class IrEnumDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String,
    val values: List<IrEnumValueDefinition>,
): IrTypeDefinition

@ApolloInternal
class IrInputObjectDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String,
    val inputFields: List<IrInputFieldDefinition>,
): IrTypeDefinition

class IrInputFieldDefinition(
    val name: String
)

class IrEnumValueDefinition(
    val name: String
)