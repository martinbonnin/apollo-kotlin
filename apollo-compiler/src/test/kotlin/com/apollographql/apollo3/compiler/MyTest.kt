package com.apollographql.apollo3.compiler

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.time.ExperimentalTime

class MyParams(val value1: String, val value2: Int)

@RunWith(Parameterized::class)
class MyTest(private val params: MyParams) {

  @Test
  fun testSomething() {
    // test something with params
  }

  companion object {
    @Parameterized.Parameters
    fun parameters(): Collection<MyParams> {
      return listOf([...])
    }
  }
}