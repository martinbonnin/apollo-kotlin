package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.api.Fragment
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.maybeFlatten
import com.apollographql.apollo3.compiler.codegen.java.model.ModelBuilder
import com.apollographql.apollo3.compiler.ir.IrModelGroup
import com.apollographql.apollo3.compiler.ir.IrNamedFragment
import com.squareup.javapoet.asClassName

class FragmentModelsBuilder(
    val context: JavaContext,
    val fragment: IrNamedFragment,
    modelGroup: IrModelGroup,
    private val addSuperInterface: Boolean,
    flatten: Boolean,
    flattenNamesInOrder: Boolean
) : JavaClassBuilder {

  private val packageName = context.layout.fragmentPackageName(fragment.filePath)

  /**
   * Fragments need to be flattened at depth 1 to avoid having all classes poluting the fragments package name
   */
  private val modelBuilders = modelGroup.maybeFlatten(flatten, flattenNamesInOrder, 1).flatMap { it.models }
      .map {
        ModelBuilder(
            context = context,
            model = it,
            superClassName = if (addSuperInterface && it.id == fragment.dataModelGroup.baseModelId) Fragment.Data::class.asClassName() else null,
            path = listOf(packageName)
        )
      }

  override fun prepare() {
    modelBuilders.forEach { it.prepare() }
  }

  override fun build(): CodegenJavaFile {
    return CodegenJavaFile(
        packageName = packageName,
        fileName = context.layout.fragmentModelsFileName(fragment.name),
        typeSpec = modelBuilders.map { it.build() }
    )
  }
}