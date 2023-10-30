
import com.apollographql.apollo3.mockserver.CloseFrame
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.TextMessage
import com.apollographql.apollo3.mockserver.awaitWebSocketRequest
import com.apollographql.apollo3.mockserver.enqueueWebSocket
import com.apollographql.apollo3.network.toNSData
import com.apollographql.apollo3.testing.internal.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.posix.sleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class WebSocketEngineTest {

  @Test
  fun textFrames() = runTest {
    val webSocketEngine = webSocketEngine()
    val webSocketServer = MockServer()

    webSocketServer.enqueueWebSocket()
    val connection = webSocketEngine.open(webSocketServer.url())
    val request = webSocketServer.awaitWebSocketRequest()

    connection.send("Hello!")
    request.awaitMessage().apply {
      assertIs<TextMessage>(this)
      assertEquals("Hello!", text)
    }

    delay(2000)

    connection.close()
    request.awaitMessage().apply {
      assertIs<CloseFrame>(this)
      println("close code=${code} reason=${reason}")
    }

    webSocketServer.close()
  }

  @OptIn(ExperimentalForeignApi::class)
  @Test
  fun runForeverFrames() = runTest {
    val webSocketServer = MockServer()
    println("listening on: ${webSocketServer.url()}")

    webSocketServer.enqueueWebSocket()

    launch(newSingleThreadContext("martin")) {
      val task = NSURLSession.sharedSession.webSocketTaskWithURL(url = NSURL(string = webSocketServer.url().replace("http", "ws")))

      task.resume()

      task.sendMessage(NSURLSessionWebSocketMessage("Hello!")) {
        println("error: $it")
      }

      sleep(2.convert())

      task.cancelWithCloseCode(closeCode = 1001, reason = "Oopsie5".encodeToByteArray().toNSData())
    }

    val request = webSocketServer.awaitWebSocketRequest(10.seconds)
    request.awaitMessage(10.seconds).apply {
      println("message=$this")
    }

    request.awaitMessage(10.seconds).apply {
      println("message=$this")
    }

    webSocketServer.close()
  }
}
