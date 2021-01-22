package com.apollographql.apollo.compiler.frontend.ir

import okio.BufferedSink
import java.nio.charset.Charset

class FileWriter(val sink: BufferedSink) {
  private var indent = 0
  private var beginningOfLine = true

  fun indent() {
    indent += 2
  }

  fun unindent() {
    indent -= 2
    check (indent >= 0) {
      "unbalanced indent/unindent calls"
    }
  }

  private fun output(c: Char) {
    if (c == '\n') {
      beginningOfLine = true
      sink.writeUtf8CodePoint(c.toInt())
    } else {
      if (beginningOfLine) {
        sink.writeString(" ".repeat(indent), Charset.defaultCharset())
        beginningOfLine = false
      }
    }
  }

  fun write(str: String) {
    for (c in str) {
      output(c)
    }
  }
}