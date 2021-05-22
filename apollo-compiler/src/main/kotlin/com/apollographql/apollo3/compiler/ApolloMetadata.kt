package com.apollographql.apollo3.compiler

import com.apollographql.apollo3.ast.GQLFragmentDefinition
import com.apollographql.apollo3.ast.Schema
import com.apollographql.apollo3.ast.parseAsGQLDocument
import com.apollographql.apollo3.ast.toSchema
import com.apollographql.apollo3.ast.toUtf8
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/**
 * metadata generated by a previous run of [GraphQLCompiler]. The schema and fragments are stored as GraphQL document strings. This
 * slightly unfortunate because that means we will parse them twice but there isn't many alternatives as validation and IR-building takes
 * GQLDocuments as inputs. We could add our own intermediate binary format but it's not guaranteed to be any faster.
 *
 * This also means that we need to ensure the types references will stay valid so we enforce that schemaPackageName is always the same.
 */
@JsonClass(generateAdapter = true)
data class ApolloMetadata(
    /**
     * Might be null if the schema is coming from upstream
     */
    val schema: Schema?,
    /**
     * The fragments
     */
    val generatedFragments: List<MetadataFragment>,
    /**
     * The generated input objects, enums
     */
    val generatedEnums: Set<String>,
    val generatedInputObjects: Set<String>,
    val schemaPackageName: String,
    /**
     * The module name, for debug
     */
    val moduleName: String,
    val pluginVersion: String,
    val customScalarsMapping: Map<String, String>,
    val generateFragmentsAsInterfaces: Boolean
) {
  companion object {

    private val adapter by lazy {
      val schemaJsonAdapter = object : JsonAdapter<Schema>() {
        override fun fromJson(reader: JsonReader): Schema {
          val string = reader.nextString()
          return string.toSchema()
        }

        override fun toJson(writer: JsonWriter, schema: Schema?) {
          writer.value(schema!!.toGQLDocument().toUtf8())
        }
      }

      val gqlFragmentJsonAdapter = object : JsonAdapter<GQLFragmentDefinition>() {
        override fun fromJson(reader: JsonReader): GQLFragmentDefinition {
          val string = reader.nextString()
          return string.parseAsGQLDocument().getOrThrow().definitions.first() as GQLFragmentDefinition
        }

        override fun toJson(writer: JsonWriter, fragmentDefinition: GQLFragmentDefinition?) {
          writer.value(fragmentDefinition!!.toUtf8())
        }
      }

      Moshi.Builder()
          .add(Schema::class.java, schemaJsonAdapter.nullSafe())
          .add(GQLFragmentDefinition::class.java, gqlFragmentJsonAdapter.nonNull())
          .build()
          .adapter(ApolloMetadata::class.java)
    }

    fun List<ApolloMetadata>.merge(): ApolloMetadata? {
      if (isEmpty()) {
        return null
      }
      // ensure a single schema
      val rootMetadataList = filter { it.schema != null }
      check(rootMetadataList.size <= 1) {
        "Apollo: A schema is defined in multiple modules: ${rootMetadataList.map { it.moduleName }.joinToString(", ")}.\n" +
            "There should be only one root module defining the schema, check your dependencies."
      }
      check(rootMetadataList.isNotEmpty()) {
        "Apollo: Cannot find a schema in parent modules. Searched in ${map { it.moduleName }.joinToString(", ")}"
      }
      val rootMetadata = rootMetadataList.first()

      // ensure the same schemaPackageName
      map { it.schemaPackageName }.filterNotNull().distinct().let {
        check(it.size == 1) {
          "Apollo: All modules should have the same schemaPackageName. Found:" + it.joinToString(", ")
        }
      }

      // ensure the same pluginVersion
      map { it.pluginVersion }.distinct().let {
        check(it.size == 1) {
          "Apollo: All modules should be generated with the same apollo version. Found:" + it.joinToString(", ")
        }
      }

      // no need to validate distinct fragment names, this will be done later when aggregating the Fragments
      return rootMetadata.copy(
          generatedFragments = flatMap { it.generatedFragments },
          moduleName = "*",
          generatedEnums = flatMap { it.generatedEnums }.toSet(),
          generatedInputObjects = flatMap { it.generatedInputObjects }.toSet(),
      )
    }

    fun readFrom(file: File) = adapter.fromJson(file.source().buffer()) ?: error("bad metadata at ${file.absolutePath}")
  }

  fun writeTo(file: File) {
    file.sink().buffer().use {
      adapter.toJson(it, this)
    }
  }
}

@JsonClass(generateAdapter = true)
data class MetadataFragment(
    val name: String,
    val definition: GQLFragmentDefinition,
    val packageName: String,
)
