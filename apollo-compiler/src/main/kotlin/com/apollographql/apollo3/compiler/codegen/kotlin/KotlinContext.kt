package com.apollographql.apollo3.compiler.codegen.kotlin

import com.apollographql.apollo3.compiler.codegen.CgLayout

class KotlinContext(
    val layout : CgLayout,
    val resolver: KotlinResolver
)