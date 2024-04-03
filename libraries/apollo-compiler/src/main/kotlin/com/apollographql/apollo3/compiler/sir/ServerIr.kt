package com.apollographql.apollo3.compiler.sir

import com.apollographql.apollo3.annotations.ApolloInternal
import com.squareup.kotlinpoet.ClassName

@ApolloInternal
data class SirClassName(
    val packageName: String,
    val names: List<String>
) {
  fun asString(): String {
    return "$packageName.${names.joinToString(".")}"
  }
}

internal fun SirClassName.asKotlinPoet(): ClassName = ClassName(packageName, names)

@ApolloInternal
class SirFieldDefinition(
    val name: String,
    val description: String?,
    val targetName: String,
    val isFunction: Boolean,
    val type: SirType,
    val arguments: List<SirArgument>
)

@ApolloInternal
sealed interface SirArgument

@ApolloInternal
object SirExecutionContextArgument: SirArgument

@ApolloInternal
class SirGraphQLArgument(
    val name: String,
    val description: String?,
    val targetName: String,
    val type: SirType,
    /**
     * The defaultValue, encoded in GraphQL
     */
    val defaultValue: String?
): SirArgument

@ApolloInternal
sealed interface SirType

class SirNonNullType(val type: SirType): SirType
class SirListType(val type: SirType): SirType
class SirNamedType(val name: String): SirType
/**
 * There was an error resolving that type
 */
data object SirErrorType: SirType

@ApolloInternal
sealed interface SirTypeDefinition {
  val qualifiedName: String
}

@ApolloInternal
class SirScalarDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    override val qualifiedName: String,
    val aliasedName: String?,
    val description: String?,
    val coercing: SirCoercing,
): SirTypeDefinition

@ApolloInternal
class SirCoercing(
    val className: SirClassName,
    val instantiation: Instantiation
)

@ApolloInternal
enum class Instantiation {
  OBJECT,
  NO_ARG_CONSTRUCTOR,
  UNKNOWN
}

@ApolloInternal
class SirObjectDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String?,
    override val qualifiedName: String,
    val interfaces: List<String>,
    val targetClassName: SirClassName,
    val instantiation: Instantiation,
    /**
     * If this is a root type, what root it is for
     */
    val operationType: String?,
    val fields: List<SirFieldDefinition>,
): SirTypeDefinition

@ApolloInternal
class SirInterfaceDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String?,
    override val qualifiedName: String,
    val interfaces: List<String>,
    val fields: List<SirFieldDefinition>,
): SirTypeDefinition

@ApolloInternal
class SirUnionDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String?,
    override val qualifiedName: String,
    val memberTypes: List<String>,
): SirTypeDefinition

@ApolloInternal
class SirEnumDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String?,
    override val qualifiedName: String,
    val values: List<SirEnumValueDefinition>,
): SirTypeDefinition

@ApolloInternal
class SirInputObjectDefinition(
    /**
     * The GraphQL name
     */
    val name: String,
    val description: String?,
    override val qualifiedName: String,
    val inputFields: List<SirInputFieldDefinition>,
): SirTypeDefinition

class SirInputFieldDefinition(
    val name: String,
    val description: String?,
    val type: SirType,
    val defaultValue: String?
)

class SirEnumValueDefinition(
    val name: String,
    val description: String?,
    val className: SirClassName
)