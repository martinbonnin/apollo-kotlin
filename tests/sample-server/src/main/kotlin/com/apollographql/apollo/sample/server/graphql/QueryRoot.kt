package com.apollographql.apollo.sample.server.graphql

import com.apollographql.apollo3.annotations.GraphQLName
import com.apollographql.apollo3.annotations.GraphQLQueryRoot


@GraphQLName("Query")
@GraphQLQueryRoot
class QueryRoot {
  fun random(): Int = 42
  fun zero(): Int = 0
  fun direction(): Direction = Direction.NORTH
  fun valueSharedWithSubscriptions(): Int = 0
  fun secondsSinceEpoch(): Double = System.currentTimeMillis().div(1000).toDouble()
}

enum class Direction {
  NORTH,
  SOUTH
}


