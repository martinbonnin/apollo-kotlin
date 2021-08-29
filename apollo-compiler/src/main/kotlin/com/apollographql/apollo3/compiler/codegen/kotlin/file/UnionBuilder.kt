package com.apollographql.apollo3.compiler.codegen.kotlin.file

import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinContext
import com.apollographql.apollo3.compiler.codegen.kotlin.helpers.maybeAddDeprecation
import com.apollographql.apollo3.compiler.codegen.kotlin.helpers.maybeAddDescription
import com.apollographql.apollo3.compiler.ir.IrId
import com.apollographql.apollo3.compiler.ir.IrUnion
import com.apollographql.apollo3.compiler.ir.IrUnionType
import com.squareup.kotlinpoet.TypeSpec

class UnionBuilder(
    private val context: KotlinContext,
    private val union: IrUnion
): CgFileBuilder {
  private val layout = context.layout
  private val packageName = layout.typePackageName()
  private val simpleName = layout.compiledTypeName(name = union.name)

  override fun prepare() {
    context.resolver.registerIrType(IrUnionType(union.name), packageName, simpleName)
  }

  override fun build(): CgFile {
    return CgFile(
        packageName = packageName,
        fileName = simpleName,
        typeSpecs = listOf(union.typeSpec())
    )
  }

  private fun IrUnion.typeSpec(): TypeSpec {
    return TypeSpec
        .classBuilder(simpleName)
        .maybeAddDescription(description)
        .maybeAddDeprecation(deprecationReason)
        .addType(companionTypeSpec())
        .build()
  }

  private fun IrUnion.companionTypeSpec(): TypeSpec {
    return TypeSpec.companionObjectBuilder()
        .addProperty(typePropertySpec(context.resolver))
        .build()
  }
}
