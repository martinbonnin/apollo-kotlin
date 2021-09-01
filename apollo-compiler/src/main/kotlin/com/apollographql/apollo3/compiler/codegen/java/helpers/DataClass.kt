package com.apollographql.apollo3.compiler.codegen.java.helpers

import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T

/**
 * This just adds fields for now. A future version will add toString(), hashCode() and equals()
 */
fun TypeSpec.Builder.makeDataClassFromParameters(parameters: List<ParameterSpec>) = apply {
  addMethod(
      MethodSpec.constructorBuilder()
          .addParameters(parameters)
          .build()
  )

  addFields(
      parameters.map {
        FieldSpec.builder(it.type, it.name)
            .addModifiers(Modifier.FINAL)
            .initializer(it.name)
            .build()
      }
  )
}

/**
 * Same as [makeDataClassFromParameters] but takes fields instead of parameters as input
 */
fun TypeSpec.Builder.makeDataClassFromProperties(fields: List<FieldSpec>) = apply {
  addMethod(
      MethodSpec.constructorBuilder()
          .addParameters(
              fields.map {
                ParameterSpec.builder(it.type, it.name).build()
              }
          )
          .build()
  )

  addFields(fields)
}

/*
fun TypeSpec.withToStringImplementation(): TypeSpec {
  fun printFieldCode(fieldIndex: Int, fieldName: String) =
      CodeBlock.builder()
          .let { if (fieldIndex > 0) it.add(" + \", \"\n") else it.add("\n") }
          .indent()
          .add("+ \$S + \$L", "$fieldName=", fieldName)
          .unindent()
          .build()

  fun methodCode() =
      CodeBlock.builder()
          .beginControlFlow("if (\$L == null)", MEMOIZED_TO_STRING_VAR)
          .add("\$L = \$S", "\$toString", "$name{")
          .add(fieldSpecs
              .filter { !it.hasModifier(Modifier.STATIC) }
              .filter { it.name != MEMOIZED_HASH_CODE_FLAG_VAR }
              .filter { it.name != MEMOIZED_HASH_CODE_VAR }
              .filter { it.name != MEMOIZED_TO_STRING_VAR }
              .map { it.name }
              .mapIndexed(::printFieldCode)
              .fold(CodeBlock.builder(), CodeBlock.Builder::add)
              .build())
          .add(CodeBlock.builder()
              .indent()
              .add("\n+ \$S;\n", "}")
              .unindent()
              .build())
          .endControlFlow()
          .addStatement("return \$L", MEMOIZED_TO_STRING_VAR)
          .build()

  return toBuilder()
      .addField(FieldSpec.builder(ClassNames.STRING, MEMOIZED_TO_STRING_VAR, Modifier.PRIVATE, Modifier.VOLATILE,
          Modifier.TRANSIENT)
          .build())
      .addMethod(MethodSpec.methodBuilder("toString")
          .addAnnotation(Override::class.java)
          .addModifiers(Modifier.PUBLIC)
          .returns(java.lang.String::class.java)
          .addCode(methodCode())
          .build())
      .build()
}

fun TypeSpec.withEqualsImplementation(): TypeSpec {
  fun equalsFieldCode(fieldIndex: Int, field: FieldSpec) =
      CodeBlock.builder()
          .let { if (fieldIndex > 0) it.add("\n && ") else it }
          .let {
            if (field.type.isPrimitive) {
              if (field.type == TypeName.DOUBLE) {
                it.add("Double.doubleToLongBits(this.\$L) == Double.doubleToLongBits(that.\$L)",
                    field.name, field.name)
              } else {
                it.add("this.\$L == that.\$L", field.name, field.name)
              }
            } else if (field.type.annotations.contains(Annotations.NONNULL) || field.type.isOptional()) {
              it.add("this.\$L.equals(that.\$L)", field.name, field.name)
            } else {
              it.add("((this.\$L == null) ? (that.\$L == null) : this.\$L.equals(that.\$L))", field.name, field.name,
                  field.name, field.name)
            }
          }
          .build()

  fun methodCode(typeJavaClass: ClassName) =
      CodeBlock.builder()
          .beginControlFlow("if (o == this)")
          .addStatement("return true")
          .endControlFlow()
          .beginControlFlow("if (o instanceof \$T)", typeJavaClass)
          .addStatement("\$T that = (\$T) o", typeJavaClass, typeJavaClass)
          .add("return ")
          .add(fieldSpecs
              .filter { !it.hasModifier(Modifier.STATIC) }
              .filter { it.name != MEMOIZED_HASH_CODE_FLAG_VAR }
              .filter { it.name != MEMOIZED_HASH_CODE_VAR }
              .filter { it.name != MEMOIZED_TO_STRING_VAR }
              .mapIndexed(::equalsFieldCode)
              .fold(CodeBlock.builder(), CodeBlock.Builder::add)
              .build())
          .add(";\n")
          .endControlFlow()
          .addStatement("return false")
          .build()

  return toBuilder()
      .addMethod(MethodSpec.methodBuilder("equals")
          .addAnnotation(Override::class.java)
          .addModifiers(Modifier.PUBLIC)
          .returns(TypeName.BOOLEAN)
          .addParameter(ParameterSpec.builder(TypeName.OBJECT, "o").build())
          .addCode(methodCode(ClassName.get("", name)))
          .build())
      .build()
}

fun TypeSpec.withHashCodeImplementation(): TypeSpec {
  fun hashFieldCode(field: FieldSpec) =
      CodeBlock.builder()
          .addStatement("h *= 1000003")
          .let {
            if (field.type.isPrimitive) {
              when (field.type.withoutAnnotations()) {
                TypeName.DOUBLE -> it.addStatement("h ^= Double.valueOf(\$L).hashCode()", field.name)
                TypeName.BOOLEAN -> it.addStatement("h ^= Boolean.valueOf(\$L).hashCode()", field.name)
                else -> it.addStatement("h ^= \$L", field.name)
              }
            } else if (field.type.annotations.contains(Annotations.NONNULL) || field.type.isOptional()) {
              it.addStatement("h ^= \$L.hashCode()", field.name)
            } else {
              it.addStatement("h ^= (\$L == null) ? 0 : \$L.hashCode()", field.name, field.name)
            }
          }
          .build()

  fun methodCode() =
      CodeBlock.builder()
          .beginControlFlow("if (!\$L)", MEMOIZED_HASH_CODE_FLAG_VAR)
          .addStatement("int h = 1")
          .add(fieldSpecs
              .filter { !it.hasModifier(Modifier.STATIC) }
              .filter { it.name != MEMOIZED_HASH_CODE_FLAG_VAR }
              .filter { it.name != MEMOIZED_HASH_CODE_VAR }
              .filter { it.name != MEMOIZED_TO_STRING_VAR }
              .map(::hashFieldCode)
              .fold(CodeBlock.builder(), CodeBlock.Builder::add)
              .build())
          .addStatement("\$L = h", MEMOIZED_HASH_CODE_VAR)
          .addStatement("\$L = true", MEMOIZED_HASH_CODE_FLAG_VAR)
          .endControlFlow()
          .addStatement("return \$L", MEMOIZED_HASH_CODE_VAR)
          .build()

  return toBuilder()
      .addField(FieldSpec.builder(TypeName.INT, MEMOIZED_HASH_CODE_VAR, Modifier.PRIVATE, Modifier.VOLATILE,
          Modifier.TRANSIENT).build())
      .addField(FieldSpec.builder(TypeName.BOOLEAN, MEMOIZED_HASH_CODE_FLAG_VAR, Modifier.PRIVATE,
          Modifier.VOLATILE, Modifier.TRANSIENT).build())
      .addMethod(MethodSpec.methodBuilder("hashCode")
          .addAnnotation(Override::class.java)
          .addModifiers(Modifier.PUBLIC)
          .returns(TypeName.INT)
          .addCode(methodCode())
          .build())
      .build()
}


private  const val MEMOIZED_HASH_CODE_VAR: String = "\$hashCode"
private const val MEMOIZED_HASH_CODE_FLAG_VAR: String = "\$hashCodeMemoized"
private const val MEMOIZED_TO_STRING_VAR: String = "\$toString"
*/