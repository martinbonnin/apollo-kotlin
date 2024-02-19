package com.apollographql.apollo3.gradle.internal

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File

internal inline fun <reified K, reified V> Map<K,V>.asMapProvider(project: Project): Provider<Map<K, V>> = project.objects.mapProperty(K::class.java, V::class.java).also {
  it.set(this)
}

internal inline fun <reified K> Set<K>.asSetProvider(project: Project): Provider<Set<K>> = project.objects.setProperty(K::class.java).also {
  it.set(this)
}


internal fun File.asFileProvider(project: Project): Provider<RegularFile> = project.objects.fileProperty().also { it.set(this) }

internal inline fun <reified K: Any> K?.asProvider(project: Project): Provider<K> = project.provider { this }