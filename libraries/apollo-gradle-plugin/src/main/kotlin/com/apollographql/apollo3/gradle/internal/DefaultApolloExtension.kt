@file:Suppress("DEPRECATION")

package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.gradle.api.AndroidProject
import com.apollographql.apollo3.gradle.api.ApolloAttributes
import com.apollographql.apollo3.gradle.api.ApolloDependencies
import com.apollographql.apollo3.gradle.api.ApolloExtension
import com.apollographql.apollo3.gradle.api.ApolloGradleToolingModel
import com.apollographql.apollo3.gradle.api.Service
import com.apollographql.apollo3.gradle.api.androidExtension
import com.apollographql.apollo3.gradle.api.isKotlinMultiplatform
import com.apollographql.apollo3.gradle.api.javaConvention
import com.apollographql.apollo3.gradle.api.kotlinMultiplatformExtension
import com.apollographql.apollo3.gradle.api.kotlinProjectExtension
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Usage
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsTarget
import java.io.File
import java.util.concurrent.Callable
import javax.inject.Inject

abstract class DefaultApolloExtension(
    private val project: Project,
    private val defaultService: DefaultService,
) : ApolloExtension, Service by defaultService {

  private var codegenOnGradleSyncConfigured: Boolean = false
  private val services = mutableListOf<DefaultService>()
  private val checkVersionsTask: TaskProvider<Task>
  private val generateApolloSources: TaskProvider<Task>
  private var hasExplicitService = false
  private val adhocComponentWithVariants: AdhocComponentWithVariants
  private val apolloMetadataConfiguration: Configuration
  private val pendingDownstreamDependencies: MutableMap<String, List<String>> = mutableMapOf()

  internal fun getServiceInfos(project: Project): List<ApolloGradleToolingModel.ServiceInfo> = services.map { service ->
    DefaultServiceInfo(
        name = service.name,
        schemaFiles = service.schemaFilesSnapshot(project),
        graphqlSrcDirs = service.graphqlSourceDirectorySet.srcDirs,
        upstreamProjects = service.upstreamDependencies.filterIsInstance<ProjectDependency>().map { it.name }.toSet(),
        endpointUrl = service.introspection?.endpointUrl?.orNull,
        endpointHeaders = service.introspection?.headers?.orNull,
    )
  }

  internal fun registerDownstreamProject(serviceName: String, projectPath: String) {
    val existingService = services.firstOrNull {
      it.name == serviceName
    }
    if (existingService != null) {
      existingService.isADependencyOf(project.rootProject.project(projectPath))
    } else {
      pendingDownstreamDependencies.compute(serviceName) { _, oldValue ->
        oldValue.orEmpty() + projectPath
      }
    }
  }

  internal fun getServiceTelemetryData(): List<ApolloGradleToolingModel.TelemetryData.ServiceTelemetryData> = services.map { service ->
    DefaultServiceTelemetryData(
        codegenModels = service.codegenModels.orNull,
        warnOnDeprecatedUsages = service.warnOnDeprecatedUsages.orNull,
        failOnWarnings = service.failOnWarnings.orNull,
        operationManifestFormat = service.operationManifestFormat.orNull,
        generateKotlinModels = service.generateKotlinModels.orNull,
        languageVersion = service.languageVersion.orNull,
        useSemanticNaming = service.useSemanticNaming.orNull,
        addJvmOverloads = service.addJvmOverloads.orNull,
        generateAsInternal = service.generateAsInternal.orNull,
        generateFragmentImplementations = service.generateFragmentImplementations.orNull,
        generateQueryDocument = service.generateQueryDocument.orNull,
        generateSchema = service.generateSchema.orNull,
        generateOptionalOperationVariables = service.generateOptionalOperationVariables.orNull,
        generateDataBuilders = service.generateDataBuilders.orNull,
        generateModelBuilders = service.generateModelBuilders.orNull,
        generateMethods = service.generateMethods.orNull,
        generatePrimitiveTypes = service.generatePrimitiveTypes.orNull,
        generateInputBuilders = service.generateInputBuilders.orNull,
        nullableFieldStyle = service.nullableFieldStyle.orNull,
        decapitalizeFields = service.decapitalizeFields.orNull,
        jsExport = service.jsExport.orNull,
        addTypename = service.addTypename.orNull,
        flattenModels = service.flattenModels.orNull,
        fieldsOnDisjointTypesMustMerge = service.fieldsOnDisjointTypesMustMerge.orNull,
        generateApolloMetadata = service.generateApolloMetadata.orNull,

        // Options for which we don't mind the value but want to know they are used
        usedOptions = mutableSetOf<String>().apply {
          if (service.includes.isPresent) add("includes")
          if (service.excludes.isPresent) add("excludes")
          @Suppress("DEPRECATION")
          if (service.sourceFolder.isPresent) add("excludes")
          @Suppress("DEPRECATION")
          if (service.schemaFile.isPresent) add("schemaFile")
          if (!service.schemaFiles.isEmpty) add("schemaFiles")
          if (service.scalarAdapterMapping.isNotEmpty()) {
            add("mapScalarAdapterExpression")
          } else if (service.scalarTypeMapping.isNotEmpty()) {
            add("mapScalar")
          }
          if (service.operationManifest.isPresent) add("operationManifest")
          if (service.generatedSchemaName.isPresent) add("generatedSchemaName")
          if (service.debugDir.isPresent) add("debugDir")
          if (service.sealedClassesForEnumsMatching.isPresent) add("sealedClassesForEnumsMatching")
          if (service.classesForEnumsMatching.isPresent) add("classesForEnumsMatching")
          if (service.outputDir.isPresent) add("outputDir")
          if (service.alwaysGenerateTypesMatching.isPresent) add("alwaysGenerateTypesMatching")
          if (service.introspection != null) add("introspection")
          if (service.registry != null) add("registry")
          if (service.upstreamDependencies.isNotEmpty()) add("dependsOn")
          if (service.downstreamDependencies.isNotEmpty()) add("isADependencyOf")
        },
    )
  }

  internal val serviceCount: Int
    get() = services.size

  @get:Inject
  protected abstract val softwareComponentFactory: SoftwareComponentFactory

  // Called when the plugin is applied
  init {
    require(GradleVersion.current() >= GradleVersion.version(MIN_GRADLE_VERSION)) {
      "apollo-android requires Gradle version $MIN_GRADLE_VERSION or greater"
    }

    adhocComponentWithVariants = softwareComponentFactory.adhoc("apollo")
    project.components.add(adhocComponentWithVariants)

    checkVersionsTask = registerCheckVersionsTask()

    /**
     * An aggregate task to easily generate all models
     */
    generateApolloSources = project.tasks.register(ModelNames.generateApolloSources()) {
      it.group = TASK_GROUP
      it.description = "Generate Apollo models for all services"
    }


    apolloMetadataConfiguration = project.configurations.create(ModelNames.metadataConfiguration()) {
      it.isCanBeConsumed = false
      it.isCanBeResolved = false
    }

    project.afterEvaluate {
      @Suppress("DEPRECATION")
      val hasApolloBlock = !defaultService.graphqlSourceDirectorySet.isEmpty
          || defaultService.schemaFile.isPresent
          || !defaultService.schemaFiles.isEmpty
          || defaultService.alwaysGenerateTypesMatching.isPresent
          || defaultService.scalarTypeMapping.isNotEmpty()
          || defaultService.scalarAdapterMapping.isNotEmpty()
          || defaultService.excludes.isPresent
          || defaultService.includes.isPresent
          || defaultService.failOnWarnings.isPresent
          || defaultService.generateApolloMetadata.isPresent
          || defaultService.generateAsInternal.isPresent
          || defaultService.codegenModels.isPresent
          || defaultService.addTypename.isPresent
          || defaultService.generateFragmentImplementations.isPresent
          || defaultService.requiresOptInAnnotation.isPresent
          || defaultService.packageName.isPresent

      if (hasApolloBlock) {
        val packageNameLine = if (defaultService.packageName.isPresent) {
          "packageName.set(\"${defaultService.packageName.get()}\")"
        } else {
          "packageNamesFromFilePaths()"
        }
        error("""
            Apollo: using the default service is not supported anymore. Please define your service explicitly:
            
            apollo {
              service("service") {
                $packageNameLine
              }
            }
          """.trimIndent())
      }

      maybeLinkSqlite()
      checkForLegacyJsTarget()
      checkApolloMetadataIsEmpty()
    }
  }

  private fun checkApolloMetadataIsEmpty() {
    check(apolloMetadataConfiguration.dependencies.isEmpty()) {
      val projectLines = apolloMetadataConfiguration.dependencies.map {
        when (it) {
          is ProjectDependency -> "project(\"${it.dependencyProject.path}\")"
          is ExternalModuleDependency -> "\"group:artifact:version\""
          else -> "project(\":foo\")"

        }
      }.joinToString("\n") { "    dependsOn($it)" }

      """
        Apollo: using apolloMetadata is not supported anymore. Please use `dependsOn`:
         
        apollo {
          service("service") {
        $projectLines
          }
        }
      """.trimIndent()
    }
  }

  private fun checkForLegacyJsTarget() {
    val kotlin = project.extensions.findByName("kotlin") as? KotlinMultiplatformExtension
    val hasLegacyJsTarget = kotlin?.targets?.any { target -> target is KotlinJsTarget && target.irTarget == null } == true
    check(!hasLegacyJsTarget) {
      "Apollo: LEGACY js target is not supported by Apollo, please use IR."
    }
  }

  private fun maybeLinkSqlite() {
    val doLink = when (linkSqlite.orNull) {
      false -> return // explicit opt-out
      true -> true // explicit opt-in
      null -> { // default: automatic detection
        project.configurations.any {
          it.dependencies.any {
            // Try to detect if a native version of apollo-normalized-cache-sqlite is in the classpath
            it.name.contains("apollo-normalized-cache-sqlite")
                && !it.name.contains("jvm")
                && !it.name.contains("android")
          }
        }
      }
    }

    if (doLink) {
      linkSqlite(project)
    }
  }

  /**
   * Call from users to explicitly register a service or by the plugin to register the implicit service
   */
  override fun service(name: String, action: Action<Service>) {
    hasExplicitService = false

    val service = project.objects.newInstance(DefaultService::class.java, project, name)
    action.execute(service)

    registerService(service)

    maybeConfigureCodegenOnGradleSync()
  }

  // See https://twitter.com/Sellmair/status/1619308362881187840
  private fun maybeConfigureCodegenOnGradleSync() {
    if (codegenOnGradleSyncConfigured) {
      return
    }

    codegenOnGradleSyncConfigured = true
    if (this.generateSourcesDuringGradleSync.getOrElse(false)) {
      project.tasks.maybeCreate("prepareKotlinIdeaImport").dependsOn(generateApolloSources)
    }
  }

  // Gradle will consider the task never UP-TO-DATE if we pass a lambda to doLast()
  @Suppress("ObjectLiteralToLambda")
  private fun registerCheckVersionsTask(): TaskProvider<Task> {
    return project.tasks.register(ModelNames.checkApolloVersions()) {
      val outputFile = BuildDirLayout.versionCheck(project)

      it.inputs.property("allVersions", Callable {
        val allDeps = (
            getDeps(project.rootProject.buildscript.configurations) +
                getDeps(project.buildscript.configurations) +
                getDeps(project.configurations)

            )
        allDeps.distinct().sorted()
      })
      it.outputs.file(outputFile)

      it.doLast(object : Action<Task> {
        override fun execute(t: Task) {
          val allVersions = it.inputs.properties["allVersions"] as List<*>

          check(allVersions.size <= 1) {
            "Apollo: All apollo versions should be the same. Found:\n$allVersions"
          }

          val version = allVersions.firstOrNull()

          outputFile.get().asFile.writeText("All versions are consistent: $version")
        }
      })
    }
  }

  private fun createConfiguration(
      name: String,
      isCanBeConsumed: Boolean,
      extendsFrom: Configuration?,
      usage: String,
      serviceName: String,
  ): Configuration {
    return project.configurations.create(name) {
      it.isCanBeConsumed = isCanBeConsumed
      it.isCanBeResolved = !isCanBeConsumed

      if (extendsFrom != null) {
        it.extendsFrom(extendsFrom)
      }

      it.attributes {
        it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, usage))
        it.attribute(ApolloAttributes.APOLLO_SERVICE_ATTRIBUTE, project.objects.named(ApolloAttributes.Service::class.java, serviceName))
      }
    }
  }

  private fun registerService(service: DefaultService) {
    check(services.find { it.name == service.name } == null) {
      "There is already a service named ${service.name}, please use another name"
    }
    services.add(service)

    if (service.graphqlSourceDirectorySet.isReallyEmpty) {
      @Suppress("DEPRECATION")
      val sourceFolder = service.sourceFolder.getOrElse("")
      if (sourceFolder.isNotEmpty()) {
        project.logger.lifecycle("Apollo: using 'sourceFolder' is deprecated, please replace with 'srcDir(\"src/${project.mainSourceSet()}/graphql/$sourceFolder\")'")
      }
      val dir = File(project.projectDir, "src/${project.mainSourceSet()}/graphql/$sourceFolder")

      service.graphqlSourceDirectorySet.srcDir(dir)
    }
    service.graphqlSourceDirectorySet.include(service.includes.getOrElse(listOf("**/*.graphql", "**/*.gql")))
    service.graphqlSourceDirectorySet.exclude(service.excludes.getOrElse(emptyList()))

    val otherOptionsConsumerConfiguration = createConfiguration(
        name = ModelNames.otherOptionsConsumerConfiguration(service),
        isCanBeConsumed = false,
        extendsFrom = null,
        usage = USAGE_APOLLO_OTHER_OPTIONS,
        serviceName = service.name,
    )

    val otherOptionsProducerConfiguration = createConfiguration(
        name = ModelNames.otherOptionsProducerConfiguration(service),
        isCanBeConsumed = true,
        extendsFrom = otherOptionsConsumerConfiguration,
        usage = USAGE_APOLLO_OTHER_OPTIONS,
        serviceName = service.name,
    )

    val pluginConfiguration = project.configurations.create(ModelNames.pluginConfiguration(service)) {
      it.isCanBeConsumed = false
      it.isCanBeResolved = true
    }

    pluginConfiguration.dependencies.add(project.dependencies.create("com.apollographql.apollo3:apollo-gradle-plugin-external:$APOLLO_VERSION"))
    service.pluginDependencies.forEach {
      pluginConfiguration.dependencies.add(it)
    }

    val operationOutputConnection: Service.OperationOutputConnection
    val directoryConnection: Service.DirectoryConnection

    val optionsTaskProvider = registerOptionsTask(project, service, otherOptionsConsumerConfiguration)
    if (!service.isMultiModule()) {
      val task = registerGenerateApolloSourcesTask2(project, optionsTaskProvider, service, pluginConfiguration)

      operationOutputConnection = Service.OperationOutputConnection(
          task = task,
          operationOutputFile = task.flatMap { it.operationManifestFile }
      )

      directoryConnection = DefaultDirectoryConnection(
          project = project,
          task = task,
          outputDir = task.flatMap { it.outputDirectory }
      )
    } else {
      val codegenSchemaConsumerConfiguration = createConfiguration(
          name = ModelNames.codegenSchemaConsumerConfiguration(service),
          isCanBeConsumed = false,
          extendsFrom = null,
          usage = USAGE_APOLLO_CODEGEN_SCHEMA,
          serviceName = service.name,
      )

      val codegenSchemaProducerConfiguration = createConfiguration(
          name = ModelNames.codegenSchemaProducerConfiguration(service),
          isCanBeConsumed = true,
          extendsFrom = codegenSchemaConsumerConfiguration,
          usage = USAGE_APOLLO_CODEGEN_SCHEMA,
          serviceName = service.name,
      )

      val upstreamIrConsumerConfiguration = createConfiguration(
          name = ModelNames.upstreamIrConsumerConfiguration(service),
          isCanBeConsumed = false,
          extendsFrom = null,
          usage = USAGE_APOLLO_UPSTREAM_IR,
          serviceName = service.name,
      )

      val upstreamIrProducerConfiguration = createConfiguration(
          name = ModelNames.upstreamIrProducerConfiguration(service),
          isCanBeConsumed = true,
          extendsFrom = upstreamIrConsumerConfiguration,
          usage = USAGE_APOLLO_UPSTREAM_IR,
          serviceName = service.name,
      )

      val downstreamIrConsumerConfiguration = createConfiguration(
          name = ModelNames.downstreamIrConsumerConfiguration(service),
          isCanBeConsumed = false,
          extendsFrom = null,
          usage = USAGE_APOLLO_DOWNSTREAM_IR,
          serviceName = service.name,
      )

      val downstreamIrProducerConfiguration = createConfiguration(
          name = ModelNames.downstreamIrProducerConfiguration(service),
          isCanBeConsumed = true,
          extendsFrom = downstreamIrConsumerConfiguration,
          usage = USAGE_APOLLO_DOWNSTREAM_IR,
          serviceName = service.name,
      )

      val codegenMetadataConsumerConfiguration = createConfiguration(
          name = ModelNames.codegenMetadataConsumerConfiguration(service),
          isCanBeConsumed = false,
          extendsFrom = null,
          usage = USAGE_APOLLO_CODEGEN_METADATA,
          serviceName = service.name,
      )

      val codegenMetadataProducerConfiguration = createConfiguration(
          name = ModelNames.codegenMetadataProducerConfiguration(service),
          isCanBeConsumed = true,
          extendsFrom = codegenMetadataConsumerConfiguration,
          usage = USAGE_APOLLO_CODEGEN_METADATA,
          serviceName = service.name,
      )

      /**
       * Tasks
       */
      val codegenSchemaTaskProvider = if (service.isSchemaModule()) {
        registerCodegenSchemaTask(
            project = project,
            service = service,
            optionsTaskProvider = optionsTaskProvider,
            schemaConsumerConfiguration = codegenSchemaConsumerConfiguration
        )
      } else {
        check(service.scalarTypeMapping.isEmpty()) {
          "Apollo: custom scalars are not used in non-schema module. Add custom scalars to your schema module."
        }
        check(!service.generateDataBuilders.isPresent) {
          "Apollo: generateDataBuilders is not used in non-schema module. Add generateDataBuilders to your schema module."
        }

        null
      }

      val irOperationsTaskProvider = registerIrOperationsTask(
          project = project,
          service = service,
          schemaConsumerConfiguration = codegenSchemaConsumerConfiguration,
          schemaTaskProvider = codegenSchemaTaskProvider,
          irOptionsTaskProvider = optionsTaskProvider,
          upstreamIrFiles = upstreamIrConsumerConfiguration)

      val sourcesFromIrTaskProvider = registerSourcesFromIrTask(
          project = project,
          service = service,
          schemaConsumerConfiguration = codegenSchemaConsumerConfiguration,
          generateOptionsTaskProvider = optionsTaskProvider,
          codegenSchemaTaskProvider = codegenSchemaTaskProvider,
          downstreamIrOperations = downstreamIrConsumerConfiguration,
          irOperationsTaskProvider = irOperationsTaskProvider,
          upstreamCodegenMetadata = codegenMetadataConsumerConfiguration,
          classpath = pluginConfiguration,
      )

      operationOutputConnection = Service.OperationOutputConnection(
          task = sourcesFromIrTaskProvider,
          operationOutputFile = sourcesFromIrTaskProvider.flatMap { it.operationManifestFile }
      )

      directoryConnection = DefaultDirectoryConnection(
          project = project,
          task = sourcesFromIrTaskProvider,
          outputDir = sourcesFromIrTaskProvider.flatMap { it.outputDir }
      )

      project.artifacts {
        if (codegenSchemaTaskProvider != null) {
          it.add(codegenSchemaProducerConfiguration.name, codegenSchemaTaskProvider.flatMap { it.codegenSchemaFile }) {
            it.classifier = "codegen-schema-${service.name}"
          }
          it.add(otherOptionsProducerConfiguration.name, optionsTaskProvider.flatMap { it.otherOptions }) {
            it.classifier = "other-options-${service.name}"
          }
        }
        it.add(upstreamIrProducerConfiguration.name, irOperationsTaskProvider.flatMap { it.irOperationsFile }) {
          it.classifier = "ir-${service.name}"
        }
        it.add(downstreamIrProducerConfiguration.name, irOperationsTaskProvider.flatMap { it.irOperationsFile }) {
          it.classifier = "ir-${service.name}"
        }
        it.add(codegenMetadataProducerConfiguration.name, sourcesFromIrTaskProvider.flatMap { it.metadataOutputFile }) {
          it.classifier = "codegen-metadata-${service.name}"
        }
      }

      adhocComponentWithVariants.addVariantsFromConfiguration(codegenMetadataProducerConfiguration) {}
      adhocComponentWithVariants.addVariantsFromConfiguration(upstreamIrProducerConfiguration) {}
      adhocComponentWithVariants.addVariantsFromConfiguration(codegenSchemaProducerConfiguration) {}
      adhocComponentWithVariants.addVariantsFromConfiguration(otherOptionsProducerConfiguration) {}

      service.upstreamDependencies.forEach {
        otherOptionsConsumerConfiguration.dependencies.add(it)
        codegenSchemaConsumerConfiguration.dependencies.add(it)
        upstreamIrConsumerConfiguration.dependencies.add(it)
        codegenMetadataConsumerConfiguration.dependencies.add(it)
      }

      val pending = pendingDownstreamDependencies.get(name)
      if (pending != null) {
        pending.forEach {
          service.isADependencyOf(project.project(it))
        }
      }
      service.downstreamDependencies.forEach {
        downstreamIrConsumerConfiguration.dependencies.add(it)
      }
    }

    if (project.hasKotlinPlugin()) {
      checkKotlinPluginVersion(project)
    }

    check(service.operationOutputAction == null || service.operationManifestAction == null) {
      "Apollo: it is an error to set both operationOutputAction and operationManifestAction. Remove operationOutputAction"
    }
    if (service.operationOutputAction != null) {
      service.operationOutputAction!!.execute(operationOutputConnection)
    }
    if (service.operationManifestAction != null) {
      service.operationManifestAction!!.execute(
          Service.OperationManifestConnection(
              operationOutputConnection.task,
              operationOutputConnection.operationOutputFile
          )
      )
    }
    maybeRegisterRegisterOperationsTasks(project, service, operationOutputConnection)

    if (service.outputDirAction == null) {
      service.outputDirAction = defaultOutputDirAction
    }
    service.outputDirAction!!.execute(directoryConnection)

    directoryConnection.task.configure {
      it.dependsOn(checkVersionsTask)
    }
    generateApolloSources.configure {
      it.dependsOn(directoryConnection.task)
    }

    registerDownloadSchemaTasks(service)

    service.generateApolloMetadata.disallowChanges()
    service.registered = true
  }

  private fun registerSourcesFromIrTask(
      project: Project,
      service: DefaultService,
      schemaConsumerConfiguration: Configuration,
      codegenSchemaTaskProvider: TaskProvider<GenerateCodegenSchemaTask>?,
      generateOptionsTaskProvider: TaskProvider<GenerateOptionsTask>,
      downstreamIrOperations: FileCollection,
      irOperationsTaskProvider: TaskProvider<GenerateIrOperationsTask>,
      upstreamCodegenMetadata: Configuration,
      classpath: FileCollection,
  ): TaskProvider<GenerateSourcesFromIrTask> {
    val extractUsedCoordinates = project.registerExtractUsedCoordinatesTask(
        taskName = ModelNames.extractUsedCoordinates(service),
        downstreamIrOperations = downstreamIrOperations
    )
    return project.registerGenerateSourcesFromIrTask(
        taskName = ModelNames.generateApolloSources(service),
        taskGroup = TASK_GROUP,
        taskDescription = "Generate Apollo models for service '${service.name}'",
        extraClasspath = classpath,
        codegenOptions = generateOptionsTaskProvider.flatMap { it.codegenOptions },
        outputDir = service.outputDir.orElse(BuildDirLayout.outputDir(project, service)),
        codegenSchemas = project.files(schemaConsumerConfiguration).apply {
          if (codegenSchemaTaskProvider != null) {
            from(codegenSchemaTaskProvider.flatMap { it.codegenSchemaFile })
          }
        },
        irOperations = irOperationsTaskProvider.flatMap { it.irOperationsFile },
        upstreamMetadata = upstreamCodegenMetadata,
        downstreamUsedCoordinates = extractUsedCoordinates.flatMap { it.usedCoordinates }
    )
  }

  private fun registerOptionsTask(
      project: Project,
      service: DefaultService,
      upstreamOtherOptions: FileCollection,
  ): TaskProvider<GenerateOptionsTask> {
    return project.registerGenerateOptionsTask(
        taskName = ModelNames.generateApolloOptions(service),
      taskGroup = TASK_GROUP,
      taskDescription = "Generate Apollo options for service '${service.name}'",

      scalarTypeMapping = service.scalarTypeMapping.asMapProvider(project),
      scalarAdapterMapping = service.scalarAdapterMapping.asMapProvider(project),
      generateDataBuilders = service.generateDataBuilders,
      codegenModels = service.codegenModels,
      addTypename = service.addTypename,
      fieldsOnDisjointTypesMustMerge = service.fieldsOnDisjointTypesMustMerge,
      decapitalizeFields = service.decapitalizeFields,
      flattenModels = service.flattenModels,
      warnOnDeprecatedUsages = service.warnOnDeprecatedUsages,
      failOnWarnings = service.failOnWarnings,
      generateOptionalOperationVariables = service.generateOptionalOperationVariables,
      alwaysGenerateTypesMatching = service.alwaysGenerateTypesMatching,
      generateKotlinModels = service.generateKotlinModels,
      languageVersion = service.languageVersion,
      packageName = service.packageName,
      rootPackageName = service.rootPackageName.asProvider(project),
      useSemanticNaming = service.useSemanticNaming,
      generateFragmentImplementations = service.generateFragmentImplementations,
      generateMethods = service.generateMethods,
      generateQueryDocument = service.generateQueryDocument,
      generateSchema = service.generateSchema,
      generatedSchemaName = service.generatedSchemaName,
      operationManifestFormat = service.operationManifestFormat(),
      generateModelBuilders = service.generateModelBuilders,
      classesForEnumsMatching = service.classesForEnumsMatching,
      generatePrimitiveTypes = service.generatePrimitiveTypes,
      nullableFieldStyle = service.nullableFieldStyle,
      sealedClassesForEnumsMatching = service.sealedClassesForEnumsMatching,
      generateAsInternal = service.generateAsInternal,
      generateInputBuilders = service.generateInputBuilders,
      addJvmOverloads = service.addJvmOverloads,
      requiresOptInAnnotation = service.requiresOptInAnnotation,
      jsExport = service.jsExport,
      upstreamOtherOptions = upstreamOtherOptions,
      javaPluginApplied = project.hasJavaPlugin().asProvider(project),
      kgpVersion = project.apolloGetKotlinPluginVersion().asProvider(project),
      kmp = project.isKotlinMultiplatform.asProvider(project),
      generateAllTypes = (service.isSchemaModule() && service.isMultiModule() && service.downstreamDependencies.isEmpty()).asProvider(project),
    )
  }

  private fun registerIrOperationsTask(
      project: Project,
      service: DefaultService,
      schemaConsumerConfiguration: Configuration,
      schemaTaskProvider: TaskProvider<GenerateCodegenSchemaTask>?,
      irOptionsTaskProvider: TaskProvider<GenerateOptionsTask>,
      upstreamIrFiles: Configuration,
  ): TaskProvider<GenerateIrOperationsTask> {
    return project.registerGenerateIrOperationsTask(
        taskName = ModelNames.generateApolloIrOperations(service),
        taskGroup = TASK_GROUP,
        taskDescription = "Generate Apollo IR operations for service '${service.name}'",
        codegenSchemaFiles = project.files(schemaConsumerConfiguration).apply {
          if (schemaTaskProvider != null) {
            from(schemaTaskProvider.flatMap { it.codegenSchemaFile })
          }
        },
        graphqlFiles = service.graphqlSourceDirectorySet,
        sourceRoots = service.graphqlSourceDirectorySet.srcDirs.map { it.absolutePath }.toSet().asSetProvider(project),
        upstreamIrFiles = upstreamIrFiles,
        irOptions = irOptionsTaskProvider.flatMap { it.irOptionsFile }
    )
  }

  private fun registerCodegenSchemaTask(
      project: Project,
      service: DefaultService,
      optionsTaskProvider: TaskProvider<GenerateOptionsTask>,
      schemaConsumerConfiguration: Configuration,
  ): TaskProvider<GenerateCodegenSchemaTask> {
    return project.registerGenerateCodegenSchemaTask(
        taskName = ModelNames.generateApolloCodegenSchema(service),
        taskGroup = TASK_GROUP,
        taskDescription = "Generate Apollo schema for service '${service.name}'",
        schemaFiles = service.schemaFiles(project),
        fallbackSchemaFiles = service.fallbackSchemaFiles(project),
        sourceRoots = service.graphqlSourceDirectorySet.srcDirs.map { it.absolutePath }.toSet().asSetProvider(project),
        upstreamSchemaFiles = schemaConsumerConfiguration,
        codegenSchemaOptionsFile = optionsTaskProvider.flatMap { it.codegenSchemaOptionsFile }
    )
  }

  private fun maybeRegisterRegisterOperationsTasks(
      project: Project,
      service: DefaultService,
      operationOutputConnection: Service.OperationOutputConnection,
  ) {
    val registerOperationsConfig = service.registerOperationsConfig
  }

  /**
   * The default wiring.
   */
  private val defaultOutputDirAction = Action<Service.DirectoryConnection> { connection ->
    when {
      project.kotlinMultiplatformExtension != null -> {
        connection.connectToKotlinSourceSet("commonMain")
      }

      project.androidExtension != null -> {
        // The default service is created from `afterEvaluate` and it looks like it's too late to register new sources
        connection.connectToAndroidSourceSet("main")
      }

      project.kotlinProjectExtension != null -> {
        connection.connectToKotlinSourceSet("main")
      }

      project.javaConvention != null -> {
        connection.connectToJavaSourceSet("main")
      }

      else -> throw IllegalStateException("Cannot find a Java/Kotlin extension, please apply the kotlin or java plugin")
    }
  }

  private fun registerGenerateApolloSourcesTask2(
      project: Project,
      optionsTaskProvider: TaskProvider<GenerateOptionsTask>,
      service: DefaultService,
      classpath: FileCollection,
  ): TaskProvider<GenerateApolloSourcesTask> {
    return project.registerGenerateApolloSourcesTask(
        taskName = ModelNames.generateApolloSources(service),
        taskGroup =  TASK_GROUP,
        taskDescription = "Generate Apollo models for service '${service.name}'",
        extraClasspath = classpath,
        schemaFiles = service.schemaFiles(project),
        fallbackSchemaFiles = service.fallbackSchemaFiles(project),
        graphqlFiles = service.graphqlSourceDirectorySet,
        sourceRoots = project.provider { service.graphqlSourceDirectorySet.srcDirs.map { it.absolutePath }.toSet() },
        codegenSchemaOptions = optionsTaskProvider.map { it.codegenSchemaOptionsFile.get() },
        irOptions =  optionsTaskProvider.map { it.irOptionsFile.get() },
        codegenOptions = optionsTaskProvider.map { it.codegenOptions.get() },
        outputDirectory = service.outputDir.orElse(BuildDirLayout.outputDir(project, service)),
    )
  }

  private fun registerDownloadSchemaTasks(service: DefaultService) {
  }

  override fun createAllAndroidVariantServices(
      sourceFolder: String,
      nameSuffix: String,
      action: Action<Service>,
  ) {
    /**
     * The android plugin will call us back when the variants are ready but before `afterEvaluate`,
     * disable the default service
     */
    hasExplicitService = true

    check(!File(sourceFolder).isRooted && !sourceFolder.startsWith("../..")) {
      """
          Apollo: using 'sourceFolder = "$sourceFolder"' makes no sense with Android variants as the same generated models will be used in all variants.
          """.trimIndent()
    }

    AndroidProject.onEachVariant(project, true) { variant ->
      val name = "${variant.name}${nameSuffix.capitalized()}"

      service(name) { service ->
        action.execute(service)

        @Suppress("DEPRECATION")
        check(!service.sourceFolder.isPresent) {
          "Apollo: service.sourceFolder is not used when calling createAllAndroidVariantServices. Use the parameter instead"
        }
        variant.sourceSets.forEach { sourceProvider ->
          service.srcDir("src/${sourceProvider.name}/graphql/$sourceFolder")
        }
        (service as DefaultService).outputDirAction = Action<Service.DirectoryConnection> { connection ->
          connection.connectToAndroidVariant(variant)
        }
      }
    }
  }

  override fun createAllKotlinSourceSetServices(sourceFolder: String, nameSuffix: String, action: Action<Service>) {
    hasExplicitService = true

    check(!File(sourceFolder).isRooted && !sourceFolder.startsWith("../..")) {
      """Apollo: using 'sourceFolder = "$sourceFolder"' makes no sense with Kotlin source sets as the same generated models will be used in all source sets.
          """.trimMargin()
    }

    createAllKotlinSourceSetServices(this, project, sourceFolder, nameSuffix, action)
  }

  abstract override val linkSqlite: Property<Boolean>
  abstract override val generateSourcesDuringGradleSync: Property<Boolean>

  companion object {
    private const val TASK_GROUP = "apollo"
    const val MIN_GRADLE_VERSION = "6.8"

    private const val USAGE_APOLLO_CODEGEN_METADATA = "apollo-codegen-metadata"
    private const val USAGE_APOLLO_UPSTREAM_IR = "apollo-upstream-ir"
    private const val USAGE_APOLLO_DOWNSTREAM_IR = "apollo-downstream-ir"
    private const val USAGE_APOLLO_CODEGEN_SCHEMA = "apollo-codegen-schema"
    private const val USAGE_APOLLO_OTHER_OPTIONS = "apollo-other-options"

    private fun getDeps(configurations: ConfigurationContainer): List<String> {
      return configurations.flatMap { configuration ->
        configuration.dependencies
            .filter {
              /**
               * When using plugins {}, the group is the plugin id, not the maven group
               */
              /**
               * the "_" check is for refreshVersions,
               * see https://github.com/jmfayard/refreshVersions/issues/507
               */
              it.group in listOf("com.apollographql.apollo3", "com.apollographql.apollo3.external")
                  && it.version != "_"
            }.mapNotNull { dependency ->
              dependency.version
            }
      }
    }

    // Don't use `graphqlSourceDirectorySet.isEmpty` here, it doesn't work for some reason
    private val SourceDirectorySet.isReallyEmpty
      get() = sourceDirectories.isEmpty

    internal fun Project.hasJavaPlugin() = project.extensions.findByName("java") != null
    internal fun Project.hasKotlinPlugin() = project.extensions.findByName("kotlin") != null
  }

  override fun apolloKspProcessor(schema: File, service: String, packageName: String): Any {
    check(project.pluginManager.hasPlugin("com.google.devtools.ksp")) {
      "Calling apolloKspProcessor only makes sense if the 'com.google.devtools.ksp' plugin is applied"
    }

    val producer = project.configurations.create("apollo${service.capitalized()}KspProcessorProducer") {
      it.isCanBeResolved = false
      it.isCanBeConsumed = true
    }

    producer.dependencies.add(project.dependencies.create("com.apollographql.apollo3:apollo-ksp-incubating"))

    val taskProvider = project.registerGenerateKspProcessorTask(
        taskName = "generate${service.capitalized()}ApolloKspProcessor",
        schema = schema.asFileProvider(project),
        packageName = packageName.asProvider(project),
        serviceName = service.asProvider(project),
        outputJar = BuildDirLayout.kspProcessorJar(project, service)
    )

    project.artifacts.add(producer.name, taskProvider.flatMap { it.outputJar }) {
      it.type = "jar"
    }

    return project.dependencies.project(mapOf("path" to project.path, "configuration" to producer.name))
  }

  override val deps: ApolloDependencies = ApolloDependencies(project.dependencies)
}
