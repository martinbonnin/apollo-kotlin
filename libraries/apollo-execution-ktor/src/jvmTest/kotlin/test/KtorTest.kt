package test

import com.apollographql.apollo3.execution.DefaultQueryRoot
import com.apollographql.apollo3.execution.ExecutableSchema
import com.apollographql.apollo3.execution.MainResolver
import com.apollographql.apollo3.execution.ResolveInfo
import com.apollographql.apollo3.execution.ktor.apolloModule
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import kotlin.test.Test

class MyMainResolver: MainResolver {
  override fun typename(obj: Any): String? {
    TODO("Not yet implemented")
  }

  override fun resolve(resolveInfo: ResolveInfo): Any? {
    return when (val parent = resolveInfo.parentObject) {
      is DefaultQueryRoot -> mapOf("foo" to "bar")
      else -> (parent as Map<String, *>).get(resolveInfo.fieldName)
    }
  }
}

class SimpleTest {
  @Test
  fun simpleTest() {

    val schema = """
            type Query {
                foo: String!
            }
        """.trimIndent()

    val executableSchema = ExecutableSchema.Builder()
        .schema(schema)
        .resolver(MyMainResolver())
        .build()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
      install(CORS) {
        anyHost()
        allowHeaders {
          true
        }
        allowNonSimpleContentTypes = true
      }
      apolloModule(executableSchema)
    }.start(wait = true)
  }
}
