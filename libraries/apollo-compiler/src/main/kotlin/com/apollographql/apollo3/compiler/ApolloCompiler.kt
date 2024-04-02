package com.apollographql.apollo3.compiler

import com.apollographql.apollo3.ast.DeprecatedUsage
import com.apollographql.apollo3.ast.DifferentShape
import com.apollographql.apollo3.ast.DirectiveRedefinition
import com.apollographql.apollo3.ast.GQLDefinition
import com.apollographql.apollo3.ast.GQLDirectiveDefinition
import com.apollographql.apollo3.ast.GQLDocument
import com.apollographql.apollo3.ast.GQLFragmentDefinition
import com.apollographql.apollo3.ast.GQLOperationDefinition
import com.apollographql.apollo3.ast.GQLScalarTypeDefinition
import com.apollographql.apollo3.ast.GQLSchemaDefinition
import com.apollographql.apollo3.ast.GQLTypeDefinition
import com.apollographql.apollo3.ast.IncompatibleDefinition
import com.apollographql.apollo3.ast.Issue
import com.apollographql.apollo3.ast.KOTLIN_LABS_VERSION
import com.apollographql.apollo3.ast.ParserOptions
import com.apollographql.apollo3.ast.QueryDocumentMinifier
import com.apollographql.apollo3.ast.Schema
import com.apollographql.apollo3.ast.UnknownDirective
import com.apollographql.apollo3.ast.UnusedFragment
import com.apollographql.apollo3.ast.UnusedVariable
import com.apollographql.apollo3.ast.checkEmpty
import com.apollographql.apollo3.ast.kotlinLabsDefinitions
import com.apollographql.apollo3.ast.parseAsGQLDocument
import com.apollographql.apollo3.ast.pretty
import com.apollographql.apollo3.ast.toGQLDocument
import com.apollographql.apollo3.ast.validateAsExecutable
import com.apollographql.apollo3.ast.validateAsSchemaAndAddApolloDefinition
import com.apollographql.apollo3.compiler.codegen.ExecutableSchemaLayout
import com.apollographql.apollo3.compiler.codegen.LayoutImpl
import com.apollographql.apollo3.compiler.codegen.SchemaAndOperationsLayout
import com.apollographql.apollo3.compiler.codegen.SchemaLayout
import com.apollographql.apollo3.compiler.codegen.SourceOutput
import com.apollographql.apollo3.compiler.codegen.java.JavaCodegen
import com.apollographql.apollo3.compiler.codegen.java.JavaOutput
import com.apollographql.apollo3.compiler.codegen.java.toSourceOutput
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinCodegen
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinOutput
import com.apollographql.apollo3.compiler.codegen.kotlin.toSourceOutput
import com.apollographql.apollo3.compiler.codegen.plus
import com.apollographql.apollo3.compiler.internal.addRequiredFields
import com.apollographql.apollo3.compiler.internal.checkApolloInlineFragmentsHaveTypeCondition
import com.apollographql.apollo3.compiler.internal.checkApolloReservedEnumValueNames
import com.apollographql.apollo3.compiler.internal.checkApolloTargetNameClashes
import com.apollographql.apollo3.compiler.internal.checkCapitalizedFields
import com.apollographql.apollo3.compiler.internal.checkConditionalFragments
import com.apollographql.apollo3.compiler.internal.checkKeyFields
import com.apollographql.apollo3.compiler.ir.IrOperations
import com.apollographql.apollo3.compiler.ir.IrOperationsBuilder
import com.apollographql.apollo3.compiler.ir.IrSchema
import com.apollographql.apollo3.compiler.ir.IrSchemaBuilder
import com.apollographql.apollo3.compiler.sir.SirTypeDefinition
import com.apollographql.apollo3.compiler.operationoutput.OperationDescriptor
import com.apollographql.apollo3.compiler.pqm.toPersistedQueryManifest
import java.io.File

object ApolloCompiler {
  interface Logger {
    fun warning(message: String)
  }

  fun buildCodegenSchema(
      schemaFiles: List<InputFile>,
      logger: Logger?,
      codegenSchemaOptions: CodegenSchemaOptions,
  ): CodegenSchema {
    val schemaDocuments = schemaFiles.map {
      it.normalizedPath to it.file.toGQLDocument(allowJson = true)
    }

    if (schemaDocuments.isEmpty()) {
      error("No schema found. Apollo needs a `.graphqls` or a `.json` schema.")
    }

    // Locate the mainSchemaDocument. It's the one that contains the operation roots
    val mainSchemaDocuments = mutableListOf<GQLDocument>()
    val otherSchemaDocuments = mutableListOf<GQLDocument>()
    var mainSchemaNormalizedPath: String? = null
    schemaDocuments.forEach {
      val document = it.second
      if (
          document.definitions.filterIsInstance<GQLSchemaDefinition>().isNotEmpty()
          || document.definitions.filterIsInstance<GQLTypeDefinition>().any { it.name == "Query" }
      ) {
        mainSchemaDocuments.add(document)
        mainSchemaNormalizedPath = it.first
      } else {
        otherSchemaDocuments.add(document)
      }
    }

    if (mainSchemaDocuments.size > 1) {
      error("Multiple schemas found:\n${mainSchemaDocuments.map { it.sourceLocation?.filePath }.joinToString("\n")}\n" +
          "Use different services for different schemas")
    } else if (mainSchemaDocuments.isEmpty()) {
      error("Schema(s) found:\n${schemaFiles.map { it.normalizedPath }.joinToString("\n")}\n" +
          "But none of them contain type definitions.")
    }
    val mainSchemaDocument = mainSchemaDocuments.single()

    // Sort the other schema document as type extensions are order sensitive
    val otherSchemaDocumentSorted = otherSchemaDocuments.sortedBy { it.sourceLocation?.filePath?.substringAfterLast(File.pathSeparator) }
    val schemaDefinitions = (listOf(mainSchemaDocument) + otherSchemaDocumentSorted).flatMap { it.definitions }
    val schemaDocument = GQLDocument(
        definitions = schemaDefinitions,
        sourceLocation = null
    )

    /**
     * TODO: use `validateAsSchema` to not automatically add the apollo definitions
     */
    val result = schemaDocument.validateAsSchemaAndAddApolloDefinition()

    val issueGroup = result.issues.group(warnOnDeprecatedUsages = true, fieldsOnDisjointTypesMustMerge = true)

    issueGroup.errors.checkEmpty()
    issueGroup.warnings.forEach {
      // Using this format, IntelliJ will parse the warning and display it in the 'run' panel
      (logger ?: defaultLogger).warning("w: ${it.sourceLocation.pretty()}: Apollo: ${it.message}")
    }

    val schema = result.value!!

    val scalarMapping = codegenSchemaOptions.scalarMapping
    checkScalars(schema, scalarMapping)

    return CodegenSchema(
        schema = schema,
        normalizedPath = mainSchemaNormalizedPath ?: "",
        scalarMapping = scalarMapping,
        generateDataBuilders = codegenSchemaOptions.generateDataBuilders ?: defaultGenerateDataBuilders
    )
  }

  /**
   * Parses the given files. Throws if there are parsing errors
   */
  private fun File.definitions(): List<GQLDefinition> {
    val definitions = mutableListOf<GQLDefinition>()
    val parseIssues = mutableListOf<Issue>()

    val parseResult = parseAsGQLDocument(options = ParserOptions.Builder().build())
    if (parseResult.issues.isNotEmpty()) {
      parseIssues.addAll(parseResult.issues)
    } else {
      // We can force cast here because we're guaranteed the parsing step will produce either issues
      // or a value
      definitions.addAll(parseResult.value!!.definitions)
    }

    // Parsing issues are fatal
    parseIssues.checkEmpty()

    return definitions
  }


  fun buildIrOperations(
      codegenSchema: CodegenSchema,
      executableFiles: List<InputFile>,
      upstreamCodegenModels: List<String>,
      upstreamFragmentDefinitions: List<GQLFragmentDefinition>,
      options: IrOptions,
      logger: Logger?,
  ): IrOperations {
    val schema = codegenSchema.schema

    val operationNameToNormalizedPath = mutableMapOf<String, String>()
    val fragmentNameToNormalizedPath = mutableMapOf<String, String>()

    /**
     * Step 1: parse the documents
     */
    val definitions = mutableListOf<GQLDefinition>()
    executableFiles.forEach { normalizedFile ->
      val fileDefinitions = normalizedFile.file.definitions()

      definitions.addAll(fileDefinitions)
      fileDefinitions.forEach {
        when (it) {
          is GQLOperationDefinition -> operationNameToNormalizedPath[it.name ?: ""] = normalizedFile.normalizedPath
          is GQLFragmentDefinition -> fragmentNameToNormalizedPath[it.name] = normalizedFile.normalizedPath
          else -> Unit
        }
      }
    }

    /**
     * Step 2, GraphQL validation
     */
    val validationResult = GQLDocument(
        definitions = definitions + upstreamFragmentDefinitions,
        sourceLocation = null
    ).validateAsExecutable(schema)

    val allIssues = mutableListOf<Issue>()
    allIssues.addAll(validationResult.issues)

    val codegenModels = defaultCodegenModels(options.codegenModels, upstreamCodegenModels)
    if (codegenModels == MODELS_RESPONSE_BASED || codegenModels == MODELS_OPERATION_BASED_WITH_INTERFACES) {
      allIssues.addAll(checkConditionalFragments(definitions))
    }

    allIssues.addAll(checkApolloReservedEnumValueNames(schema))
    allIssues.addAll(checkApolloTargetNameClashes(schema))
    allIssues.addAll(checkApolloInlineFragmentsHaveTypeCondition(definitions))

    val flattenModels = options.flattenModels ?: flattenModels(codegenModels)
    val decapitalizeFields = options.decapitalizeFields ?: defaultDecapitalizeFields
    val warnOnDeprecatedUsages = options.warnOnDeprecatedUsages ?: defaultWarnOnDeprecatedUsages
    val failOnWarnings = options.failOnWarnings ?: defaultFailOnWarnings
    val fieldsOnDisjointTypesMustMerge = options.fieldsOnDisjointTypesMustMerge ?: defaultFieldsOnDisjointTypesMustMerge
    val addTypename = options.addTypename ?: defaultAddTypename
    val generateOptionalOperationVariables = options.generateOptionalOperationVariables ?: defaultGenerateOptionalOperationVariables
    val alwaysGenerateTypesMatching = options.alwaysGenerateTypesMatching ?: defaultAlwaysGenerateTypesMatching

    if (!decapitalizeFields) {
      // When flattenModels is true, we still must check capitalized fields inside fragment spreads
      allIssues.addAll(checkCapitalizedFields(definitions, checkFragmentsOnly = flattenModels))
    }

    val issueGroup = allIssues.group(
        warnOnDeprecatedUsages,
        fieldsOnDisjointTypesMustMerge,
    )

    issueGroup.errors.checkEmpty()

    issueGroup.warnings.forEach {
      // Using this format, IntelliJ will parse the warning and display it in the 'run' panel
      (logger ?: defaultLogger).warning("w: ${it.sourceLocation.pretty()}: Apollo: ${it.message}")
    }

    if (failOnWarnings && issueGroup.warnings.isNotEmpty()) {
      throw IllegalStateException("Apollo: Warnings found and 'failOnWarnings' is true, aborting.")
    }

    /**
     * Step 3, Modify the AST to add typename and key fields
     */
    val fragmentDefinitions = (definitions.filterIsInstance<GQLFragmentDefinition>() + upstreamFragmentDefinitions).associateBy { it.name }
    val fragments = definitions.filterIsInstance<GQLFragmentDefinition>().map {
      addRequiredFields(it, addTypename, schema, fragmentDefinitions)
    }

    val operations = definitions.filterIsInstance<GQLOperationDefinition>().map {
      addRequiredFields(it, addTypename, schema, fragmentDefinitions)
    }

    // Remember the fragments with the possibly updated fragments
    val allFragmentDefinitions = (fragments + upstreamFragmentDefinitions).associateBy { it.name }

    // Check if all the key fields are present in operations and fragments
    // (do this only if there are key fields as it may be costly)
    if (schema.hasTypeWithTypePolicy()) {
      operations.forEach {
        checkKeyFields(it, schema, allFragmentDefinitions)
      }
      fragments.forEach {
        checkKeyFields(it, schema, allFragmentDefinitions)
      }
    }

    /**
     * Build the IR
     */
    return IrOperationsBuilder(
        schema = schema,
        operationDefinitions = operations,
        operationNameToNormalizedPath = operationNameToNormalizedPath,
        fragmentDefinitions = fragments,
        fragmentNameToNormalizedPath = fragmentNameToNormalizedPath,
        allFragmentDefinitions = allFragmentDefinitions,
        codegenModels = codegenModels,
        generateOptionalOperationVariables = generateOptionalOperationVariables,
        flattenModels = flattenModels,
        decapitalizeFields = decapitalizeFields,
        alwaysGenerateTypesMatching = alwaysGenerateTypesMatching,
        generateDataBuilders = codegenSchema.generateDataBuilders,
        fragmentVariableUsages = validationResult.fragmentVariableUsages
    ).build()
  }

  private fun buildIrSchema(
      codegenSchema: CodegenSchema,
      usedCoordinates: UsedCoordinates?,
  ): IrSchema {

    @Suppress("NAME_SHADOWING")
    val usedCoordinates = usedCoordinates?.mergeWith((codegenSchema.scalarMapping.keys + setOf("Int", "Float", "String", "ID", "Boolean")).associateWith { emptySet() })
        ?: codegenSchema.schema.typeDefinitions.keys.associateWith { emptySet() }

    return IrSchemaBuilder.build(
        schema = codegenSchema.schema,
        usedFields = usedCoordinates,
        alreadyVisitedTypes = emptySet(),
    )
  }

  private fun checkScalars(schema: Schema, scalarMapping: Map<String, ScalarInfo>) {
    /**
     * Generate the mapping for all scalars
     *
     * If the user specified a mapping, use it, else fallback to [Any]
     */
    val schemaScalars = schema
        .typeDefinitions
        .values
        .filterIsInstance<GQLScalarTypeDefinition>()
        .map { type -> type.name }
        .toSet()
    val unknownScalars = scalarMapping.keys.subtract(schemaScalars)
    check(unknownScalars.isEmpty()) {
      "Apollo: unknown scalar(s): ${unknownScalars.joinToString(",")}"
    }
  }

  fun buildSchemaSources(
      codegenSchema: CodegenSchema,
      usedCoordinates: UsedCoordinates?,
      codegenOptions: CodegenOptions,
      schemaLayout: SchemaLayout?,
      javaOutputTransform: Transform<JavaOutput>?,
      kotlinOutputTransform: Transform<KotlinOutput>?,
  ): SourceOutput {
    val irSchema = buildIrSchema(codegenSchema, usedCoordinates)

    val targetLanguage = defaultTargetLanguage(codegenOptions.targetLanguage, emptyList())
    codegenOptions.validate()

    val layout = schemaLayout ?: SchemaAndOperationsLayout(
        codegenSchema = codegenSchema,
        packageName = codegenOptions.packageName,
        rootPackageName = codegenOptions.rootPackageName,
        useSemanticNaming = codegenOptions.useSemanticNaming ,
        decapitalizeFields = codegenOptions.decapitalizeFields,
        generatedSchemaName = codegenOptions.generatedSchemaName,
    )

    return if (targetLanguage == TargetLanguage.JAVA) {
      JavaCodegen.buildSchemaSources(
          codegenSchema = codegenSchema,
          irSchema = irSchema,
          codegenOptions = codegenOptions,
          layout = layout,
          javaOutputTransform = javaOutputTransform
      ).toSourceOutput()
    } else {
      KotlinCodegen.buildSchemaSources(
          codegenSchema = codegenSchema,
          targetLanguage = targetLanguage,
          irSchema = irSchema,
          codegenOptions = codegenOptions,
          layout = layout,
          kotlinOutputTransform = kotlinOutputTransform
      ).toSourceOutput()
    }
  }

  fun buildSchemaAndOperationsSourcesFromIr(
      codegenSchema: CodegenSchema,
      irOperations: IrOperations,
      downstreamUsedCoordinates: UsedCoordinates?,
      upstreamCodegenMetadata: List<CodegenMetadata>,
      codegenOptions: CodegenOptions,
      layout: SchemaAndOperationsLayout?,
      @Suppress("DEPRECATION") operationOutputGenerator: OperationOutputGenerator?,
      irOperationsTransform: Transform<IrOperations>?,
      javaOutputTransform: Transform<JavaOutput>?,
      kotlinOutputTransform: Transform<KotlinOutput>?,
      operationManifestFile: File?,
  ): SourceOutput {
    @Suppress("NAME_SHADOWING")
    val irOperations = irOperations.maybeTransform(irOperationsTransform)

    val targetLanguage = defaultTargetLanguage(codegenOptions.targetLanguage, upstreamCodegenMetadata)
    codegenOptions.validate()

    val operationOutput = irOperations.operations.map {
      OperationDescriptor(
          name = it.name,
          source = QueryDocumentMinifier.minify(it.sourceWithFragments),
          type = it.operationType.name.lowercase()
      )
    }.let {
      (operationOutputGenerator ?: defaultOperationOutputGenerator).generate(it)
    }

    check(operationOutput.size == irOperations.operations.size) {
      """The number of operation IDs (${operationOutput.size}) should match the number of operations (${irOperations.operations.size}).
        |Check that all your IDs are unique.
      """.trimMargin()
    }

    val operationManifestFormat = codegenOptions.operationManifestFormat
    if ((operationManifestFormat ?: defaultOperationManifestFormat) != MANIFEST_NONE) {
      check(operationManifestFile != null) {
        "Apollo: no operationManifestFile set to output '$operationManifestFormat' operation manifest"
      }
      when (operationManifestFormat) {
        MANIFEST_OPERATION_OUTPUT -> operationOutput.writeTo(operationManifestFile)
        MANIFEST_PERSISTED_QUERY -> operationOutput.toPersistedQueryManifest().writeTo(operationManifestFile)
      }
    }


    @Suppress("NAME_SHADOWING")
    val layout = layout ?: SchemaAndOperationsLayout(
        codegenSchema = codegenSchema,
        packageName = codegenOptions.packageName,
        rootPackageName = codegenOptions.rootPackageName,
        useSemanticNaming = codegenOptions.useSemanticNaming,
        decapitalizeFields = codegenOptions.decapitalizeFields,
        generatedSchemaName = codegenOptions.generatedSchemaName,
    )

    var sourceOutput: SourceOutput? = null
    if (upstreamCodegenMetadata.isEmpty()) {
      sourceOutput = sourceOutput plus buildSchemaSources(
          codegenSchema = codegenSchema,
          usedCoordinates = downstreamUsedCoordinates?.mergeWith(irOperations.usedFields),
          codegenOptions = codegenOptions,
          schemaLayout = layout,
          javaOutputTransform = javaOutputTransform,
          kotlinOutputTransform = kotlinOutputTransform,
      )
    }
    if (targetLanguage == TargetLanguage.JAVA) {
      sourceOutput = sourceOutput plus JavaCodegen.buildOperationsSources(
          codegenSchema = codegenSchema,
          irOperations = irOperations,
          operationOutput = operationOutput,
          upstreamCodegenMetadata = upstreamCodegenMetadata + listOfNotNull(sourceOutput?.codegenMetadata),
          codegenOptions = codegenOptions,
          layout = layout,
          javaOutputTransform = javaOutputTransform,
      ).toSourceOutput()
    } else {
      sourceOutput = sourceOutput plus KotlinCodegen.buildOperationSources(
          codegenSchema = codegenSchema,
          targetLanguage = targetLanguage,
          irOperations = irOperations,
          operationOutput = operationOutput,
          upstreamCodegenMetadata = upstreamCodegenMetadata + listOfNotNull(sourceOutput?.codegenMetadata),
          codegenOptions = codegenOptions,
          layout = layout,
          kotlinOutputTransform = kotlinOutputTransform
      ).toSourceOutput()
    }

    return sourceOutput
  }

  /**
   * Compiles a set of files without serializing the intermediate results
   */
  fun buildSchemaAndOperationsSources(
      schemaFiles: List<InputFile>,
      executableFiles: List<InputFile>,
      codegenSchemaOptions: CodegenSchemaOptions,
      irOptions: IrOptions,
      codegenOptions: CodegenOptions,
      layoutFactory: LayoutFactory?,
      @Suppress("DEPRECATION") operationOutputGenerator: OperationOutputGenerator?,
      irOperationsTransform: Transform<IrOperations>?,
      javaOutputTransform: Transform<JavaOutput>?,
      kotlinOutputTransform: Transform<KotlinOutput>?,
      logger: Logger?,
      operationManifestFile: File?,
  ): SourceOutput {
    val codegenSchema = buildCodegenSchema(
        schemaFiles = schemaFiles,
        logger = logger,
        codegenSchemaOptions = codegenSchemaOptions
    )

    val irOperations = buildIrOperations(
        codegenSchema = codegenSchema,
        executableFiles = executableFiles,
        upstreamCodegenModels = emptyList(),
        upstreamFragmentDefinitions = emptyList(),
        options = irOptions,
        logger = logger
    )

    val sourceOutput = buildSchemaAndOperationsSourcesFromIr(
        codegenSchema = codegenSchema,
        irOperations = irOperations,
        downstreamUsedCoordinates = emptyMap(),
        upstreamCodegenMetadata = emptyList(),
        codegenOptions = codegenOptions,
        layout = layoutFactory?.create(codegenSchema),
        irOperationsTransform = irOperationsTransform,
        javaOutputTransform = javaOutputTransform,
        kotlinOutputTransform = kotlinOutputTransform,
        operationManifestFile = operationManifestFile,
        operationOutputGenerator = operationOutputGenerator,
    )

    return sourceOutput
  }

  fun buildExecutableSchemaSources(
      typeDefinitions: List<SirTypeDefinition>,
      packageName: String,
      serviceName: String,
  ): SourceOutput {
    @Suppress("DEPRECATION")
    val layout = object : ExecutableSchemaLayout {
      override fun packageName(): String {
        return packageName
      }
    }

    return KotlinCodegen.buildExecutableSchema(
        sirTypeDefinitions = typeDefinitions,
        layout = layout,
        serviceName = serviceName
    ).toSourceOutput()
  }
}

private enum class Severity {
  None,
  Warning,
  Error
}

internal class IssueGroup(
    val ignored: List<Issue>,
    val warnings: List<Issue>,
    val errors: List<Issue>,
)

internal fun List<Issue>.group(
    warnOnDeprecatedUsages: Boolean,
    fieldsOnDisjointTypesMustMerge: Boolean,
): IssueGroup {
  val ignored = mutableListOf<Issue>()
  val warnings = mutableListOf<Issue>()
  val errors = mutableListOf<Issue>()
  val apolloDirectives = kotlinLabsDefinitions(KOTLIN_LABS_VERSION).mapNotNull { (it as? GQLDirectiveDefinition)?.name }.toSet()

  forEach {
    val severity = when (it) {
      is DeprecatedUsage -> if (warnOnDeprecatedUsages) Severity.Warning else Severity.None
      is DifferentShape -> if (fieldsOnDisjointTypesMustMerge) Severity.Error else Severity.Warning
      is UnusedVariable -> Severity.Warning
      is UnusedFragment -> Severity.None
      is UnknownDirective -> if (it.requireDefinition) Severity.Error else Severity.Warning
      /**
       * Because some users might have added the apollo directive to their schema, we just let that through for now
       */
      is DirectiveRedefinition -> if (it.name in apolloDirectives) Severity.None else Severity.Warning
      is IncompatibleDefinition -> Severity.Warning
      else -> Severity.Error
    }

    when (severity) {
      Severity.None -> ignored.add(it)
      Severity.Warning -> warnings.add(it)
      Severity.Error -> errors.add(it)
    }
  }

  return IssueGroup(ignored, warnings, errors)
}

/**
 * An input file together with its normalizedPath
 * normalizedPath is used to compute the package name in some cases
 */
class InputFile(val file: File, val normalizedPath: String)

fun Collection<File>.toInputFiles(): List<InputFile> = map { InputFile(it, "") }

internal fun <T> T.maybeTransform(transform: Transform<T>?) = transform?.transform(this) ?: this

interface LayoutFactory {
  fun create(codegenSchema: CodegenSchema): SchemaAndOperationsLayout?
}