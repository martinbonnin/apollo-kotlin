
import com.apollographql.apollo3.mockserver.CloseFrame
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.TextMessage
import com.apollographql.apollo3.mockserver.awaitWebSocketRequest
import com.apollographql.apollo3.mockserver.enqueueWebSocket
import com.apollographql.apollo3.network.toNSData
import com.apollographql.apollo3.testing.internal.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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

  @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
  @Test
  fun runForeverFrames() = runTest {
    val webSocketServer = MockServer()
    println("listening on: ${webSocketServer.url()}")

    webSocketServer.enqueueWebSocket()

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

    ).webSocketTaskWithURL(url = NSURL(string = webSocketServer.url().replace("http", "ws")))

    task.resume()

    val request = webSocketServer.awaitWebSocketRequest(10.seconds)

    task.sendMessage(NSURLSessionWebSocketMessage("Hello!")) {
      println("error: $it")
    }

    val message = request.awaitMessage(10.seconds)
    println("message=$message")

    sleep(2.convert())

    task.cancelWithCloseCode(closeCode = 1001, reason = "Oopsie5".encodeToByteArray().toNSData())

    println("done")

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
