package com.apollographql.apollo.compiler

internal fun String.toUpperCamelCase(): String {
  val firstLetterIndex = this.indexOfFirst { it.isLetter() }
  return this.substring(0, firstLetterIndex) + this.substring(firstLetterIndex, this.length).capitalize()
}
