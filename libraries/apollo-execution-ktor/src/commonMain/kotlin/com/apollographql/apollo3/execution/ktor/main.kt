package com.apollographql.apollo3.execution.ktor

import com.apollographql.apollo3.api.ExecutionContext
import com.apollographql.apollo3.execution.ExecutableSchema
import com.apollographql.apollo3.execution.GraphQLRequest
import com.apollographql.apollo3.execution.GraphQLResponse
import com.apollographql.apollo3.execution.parseGetGraphQLRequest
import com.apollographql.apollo3.execution.parsePostGraphQLRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import okio.Buffer

private suspend fun ApplicationCall.handle(executableSchema: ExecutableSchema, result: Result<GraphQLRequest>) {
  val contentType = ContentType.parse("application/graphql-response+json")
  if (result.isFailure) {
    respondText(contentType = contentType, status = HttpStatusCode.BadRequest) {
      result.exceptionOrNull()?.message ?: ""
    }
  } else {
    respondBytes(contentType = contentType, status = io.ktor.http.HttpStatusCode.OK) {
      val response = executableSchema.execute(result.getOrThrow(), com.apollographql.apollo3.api.ExecutionContext.Empty)
      response.toByteArray()
    }
  }
}

/**
 * Probably a lot of copy operation going on down there, but we need to go in blocking land
 */
private suspend fun ByteReadChannel.buffer(): Buffer {
  val buffer = Buffer()
  val byteArray = ByteArray(8192)
  while (true) {
    if (availableForRead == 0) {
      awaitContent()
      if (availableForRead == 0) {
        break
      }
    }
    val toRead = this.availableForRead.coerceAtMost(byteArray.size)
    val read = readAvailable(byteArray, 0, toRead)
    buffer.write(byteArray, 0, read)
  }
  return buffer
}

private fun GraphQLResponse.toByteArray(): ByteArray {
  val buffer = Buffer()
  serialize(buffer)
  return buffer.readByteArray()
}

suspend fun ApplicationRequest.toGraphQLRequest(method: HttpMethod): Result<GraphQLRequest> {
  return when(method) {
    HttpMethod.Post -> receiveChannel().buffer().parsePostGraphQLRequest()
    HttpMethod.Get -> queryString().parseGetGraphQLRequest()
    else -> Result.failure(Exception("Unhandled method: $method"))
  }
}

fun Application.apolloModule(executableSchema: ExecutableSchema) {
  routing {
    post("/graphql") {
      call.handle(executableSchema, call.request.toGraphQLRequest(HttpMethod.Post))
    }
    get("/graphql") {
      call.handle(executableSchema, call.request.toGraphQLRequest(HttpMethod.Get))
    }
  }
}