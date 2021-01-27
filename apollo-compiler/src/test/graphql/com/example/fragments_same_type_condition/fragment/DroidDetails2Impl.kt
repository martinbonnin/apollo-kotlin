// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL plugin from the GraphQL queries it found.
// It should not be modified by hand.
//
package com.example.fragments_same_type_condition.fragment

import com.apollographql.apollo.api.Fragment
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.internal.ResponseAdapter
import com.example.fragments_same_type_condition.fragment.adapter.DroidDetails2Impl_ResponseAdapter
import kotlin.String

class DroidDetails2Impl : Fragment<DroidDetails2Impl.Data> {
  override fun adapter(): ResponseAdapter<Data> {
    return DroidDetails2Impl_ResponseAdapter
  }

  override fun variables(): Operation.Variables = Operation.EMPTY_VARIABLES

  /**
   * An autonomous mechanical character in the Star Wars universe
   */
  data class Data(
    override val __typename: String = "Droid",
    /**
     * This droid's primary function
     */
    override val primaryFunction: String?
  ) : DroidDetails2, Fragment.Data
}