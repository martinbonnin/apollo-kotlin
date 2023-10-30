
import com.apollographql.apollo3.mockserver.BinaryMessage
import com.apollographql.apollo3.mockserver.CloseFrame
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.TextMessage
import com.apollographql.apollo3.mockserver.awaitWebSocketRequest
import com.apollographql.apollo3.mockserver.enqueueWebSocket
import com.apollographql.apollo3.mpp.Platform
import com.apollographql.apollo3.mpp.platform
import com.apollographql.apollo3.network.toNSData
import com.apollographql.apollo3.testing.internal.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketTask
import platform.darwin.NSObject
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
    connection.send("Hello!")

    val request = webSocketServer.awaitWebSocketRequest()

    var clientMessage = request.awaitMessage()
    assertIs<TextMessage>(clientMessage)
    assertEquals("Hello!", clientMessage.text)

    delay(2000)
    connection.close()

    clientMessage = request.awaitMessage()
    assertIs<CloseFrame>(clientMessage)
    println("close code=${clientMessage.code} reason=${clientMessage.reason}")

    webSocketServer.close()
  }


  @Test
  fun binaryFrames() = runTest {
    if (platform() == Platform.Js) return@runTest // Binary frames are not supported by the JS WebSocketEngine

    val webSocketEngine = webSocketEngine()
    val webSocketServer = MockServer()

    webSocketServer.enqueueWebSocket()
    val connection = webSocketEngine.open(webSocketServer.url())
    connection.send("Hello!".encodeUtf8())

    val request = webSocketServer.awaitWebSocketRequest()

    var clientMessage = request.awaitMessage()
    assertIs<BinaryMessage>(clientMessage)
    assertEquals("Hello!", clientMessage.bytes.decodeToString())

    delay(2000)
    connection.close()

    clientMessage = request.awaitMessage()
    assertIs<CloseFrame>(clientMessage)
    println("close code=${clientMessage.code} reason=${clientMessage.reason}")

    webSocketServer.close()
  }


  @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
  @Test
  fun runForeverFrames() = runTest {
    val dispatcher = newSingleThreadContext("client")

    val webSocketServer = MockServer()
    println("listening on: ${webSocketServer.url()}")

    launch(dispatcher) {
      runClient(webSocketServer.url())
    }

    webSocketServer.enqueueWebSocket()
    val request = webSocketServer.awaitWebSocketRequest(10.seconds)

    while (true) {
      val message = request.awaitMessage(10.seconds)
      println("message=$message")
      if (message is CloseFrame) {
        break
      }
    }

    webSocketServer.close()
  }

  @OptIn(ExperimentalForeignApi::class)
  fun runClient(url: String) {
    val delegateQueue = if (NSThread.isMainThread) {
      runBlocking(Dispatchers.Default) { NSOperationQueue.currentQueue() }
    } else {
      NSOperationQueue.currentQueue()
    }

    val delegate = object : NSObject(), NSURLSessionWebSocketDelegateProtocol {
      override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) {
        println("open!")
      }
    }
    val task = NSURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.defaultSessionConfiguration,
        delegate = delegate,
        delegateQueue = delegateQueue

    ).webSocketTaskWithURL(url = NSURL(string = url.replace("http", "ws")))

    task.resume()

    task.sendMessage(NSURLSessionWebSocketMessage("Hello!")) {
      println("error: $it")
    }

    sleep(2.convert())

    task.cancelWithCloseCode(closeCode = 1001, reason = "Oopsie5".encodeToByteArray().toNSData())

    print("done")
  }
}
