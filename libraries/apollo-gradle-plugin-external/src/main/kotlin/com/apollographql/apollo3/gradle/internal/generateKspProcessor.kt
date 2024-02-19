package com.apollographql.apollo3.gradle.internal

import gratatouille.GInputFile
import gratatouille.GManuallyWired
import gratatouille.GOutputFile
import gratatouille.GTaskAction
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class Entry(
    val name: String,
    val contents: ByteArray
)

private fun metaInfResource(): Entry {
  return Entry(
      "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider",
      "apollographql.generated.ApolloProcessorProvider".encodeToByteArray()
  )
}

private fun processorClassFile(serviceName: String, packageName: String): Entry {
  return Entry(
      "apollographql/generated/ApolloProcessorProvider.class",
      ApolloProcessorProviderDump(serviceName, packageName).dump()
  )
}

private fun schema(schema: File): Entry {
  return Entry(
      "schema.graphqls",
      schema.readBytes()
  )
}
private fun ZipOutputStream.putEntry(entry: Entry) {
  val zipEntry = ZipEntry(entry.name)
  zipEntry.size = entry.contents.size.toLong()
  putNextEntry(zipEntry)
  write(entry.contents)
}

@GTaskAction
fun generateKspProcessor(
    serviceName: String,
    packageName: String,
    schema: GInputFile,
    @GManuallyWired outputJar: GOutputFile
) {
  outputJar.outputStream().let { ZipOutputStream(it) }.use {
    it.putEntry(metaInfResource())
    it.putEntry(processorClassFile(serviceName, packageName))
    it.putEntry(schema(schema))
  }
}