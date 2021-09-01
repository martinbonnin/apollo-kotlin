@file:JvmName("Assertions")
package com.apollographql.apollo3.api

import com.apollographql.apollo3.exception.ApolloException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.jvm.JvmName

@OptIn(ExperimentalContracts::class)
fun checkFieldNotMissing(value: Any?, name: String) {
  contract {
    returns() implies (value != null)
  }

  if (value == null) {
    throw ApolloException("Field $name is missing")
  }
}
