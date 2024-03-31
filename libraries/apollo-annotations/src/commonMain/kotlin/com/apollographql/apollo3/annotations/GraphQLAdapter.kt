package com.apollographql.apollo3.annotations

/**
 * Marks a given class or typealias as an adapter for the given scalar type.
 *
 * @param forScalar the name of the scalar that this adapter can handle
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS)
annotation class GraphQLAdapter(val forScalar: String)
