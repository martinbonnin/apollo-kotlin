package com.apollographql.apollo.compiler

import com.apollographql.apollo.compiler.TestUtils.checkExpected
import com.apollographql.apollo.compiler.TestUtils.testParametersForGraphQLFilesIn
import com.apollographql.apollo.compiler.frontend.GQLFragmentDefinition
import com.apollographql.apollo.compiler.frontend.GQLOperationDefinition
import com.apollographql.apollo.compiler.frontend.GraphQLParser
import com.apollographql.apollo.compiler.frontend.ir.FrontendIrBuilder
import com.apollographql.apollo.compiler.frontend.ir.toSimpleModels
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@Suppress("UNUSED_PARAMETER")
@RunWith(Parameterized::class)
class FrontendIRTest(name: String, private val graphQLFile: File) {
  @Test
  fun test() = checkExpected(graphQLFile) { schema ->
    check(schema != null) {
      "cannot find schema for ${graphQLFile.path}"
    }
    val document = GraphQLParser.parseOperations(graphQLFile, schema).orThrow()
    val ir = FrontendIrBuilder(
        schema = schema,
        metadataFragmentDefinitions = emptyList(),
        operationDefinitions = document.definitions.filterIsInstance<GQLOperationDefinition>(),
        fragmentDefinitions =  document.definitions.filterIsInstance<GQLFragmentDefinition>(),
    ).build()

    ir.toSimpleModels()
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data() = testParametersForGraphQLFilesIn("src/test/frontendir/")
  }
}
