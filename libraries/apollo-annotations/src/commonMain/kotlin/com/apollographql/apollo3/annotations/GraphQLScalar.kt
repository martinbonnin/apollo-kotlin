package com.apollographql.apollo3.annotations

import kotlin.reflect.KClass

/**
 * Marks a given class or typealias as a custom GraphQL scalar.
 *
 * ```kotlin
 * @GraphQLScalar(GeoPointCoercing::class)
 * class GeoPoint(val x: Double, val y: Double)
 * ```
 *
 * If you do not control the type, you can use a type alias:
 *
 * ```kotlin
 * @GraphQLScalar(DateCoercing::class)
 * @GraphQLName("Date")
 * typealias GraphQLDate = java.util.Date
 * ```
 *
 * When using type aliases, you may use either the alias or the original type.
 *
 * @param coercing the coercing to use with this scalar. The Coercing must be an object or a class with a no-arg constructor
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS)
@ApolloExperimental
annotation class GraphQLScalar(val coercing: KClass<*>)
