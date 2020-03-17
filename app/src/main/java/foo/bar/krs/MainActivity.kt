package foo.bar.krs

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.*
import io.ktor.network.tls.tls
import io.ktor.util.KtorExperimentalAPI
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@KtorExperimentalAPI
class MainActivity : AppCompatActivity() {

    companion object {
        const val PORT = 9876
    }

    private val inetAddress by lazy {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ip = wifiInfo.ipAddress
        val byteBuffer: ByteBuffer = ByteBuffer.allocate(4)
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        }
        byteBuffer.putInt(wifiInfo.ipAddress)

        return@lazy InetAddress.getByAddress(null, byteBuffer.array())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }


    fun connectCustomOnClick(view: View) {
        DummyRawEchoServer(PORT).start()

        GlobalScope.launch(Dispatchers.IO) {
            SocketStream(inetAddress.hostAddress, PORT, fixedThreadPoolDispatcher(10)).connectAsync()
        }
    }

    fun connectIOOnClick(view: View) {
        DummyRawEchoServer(PORT).start()

        GlobalScope.launch(Dispatchers.IO) {
            SocketStream(inetAddress.hostAddress, PORT, Dispatchers.IO).connectAsync()
        }
    }
}

@KtorExperimentalAPI
class SocketStream(private val host: String, private val port: Int, private val dispatcher: CoroutineDispatcher) {

    companion object {
        const val BUFFER_SIZE = 4096
    }

    private lateinit var writeJob: Job
    private lateinit var readJob: Job

    private val connected: AtomicBoolean = AtomicBoolean(false)

    private lateinit var socket: Socket
    private lateinit var input: ByteReadChannel
    private lateinit var output: ByteWriteChannel

    private var writeData = ByteArray(BUFFER_SIZE)
    private var readData = ByteArray(BUFFER_SIZE)

    //never called
    private val ceh = CoroutineExceptionHandler { coroutineContext, throwable ->
        println("CEH -> cc -> $coroutineContext | ex: $throwable")
        coroutineContext.cancel(kotlinx.coroutines.CancellationException("disconnect"))
        disconnect()
    }

    private suspend fun writeTask(scope: CoroutineScope) {
        while (scope.isActive) {
            if(!output.isClosedForWrite) {

                //this try catch doesn't catch the socket exception when connection aborted
                //with normal Dispatchers.IO
                try {
                    output.writeAvailable(writeData, 0, output.availableForWrite)
                } catch (cause: Throwable) {
                    println("connection error: $cause")
                }

            } else {
                println("WRITE ERROR: output closed")
                disconnect()
            }
        }
    }

    private suspend fun readTask(scope: CoroutineScope) {
        while (scope.isActive) {
            if (!input.isClosedForRead) {
                    input.readAvailable(readData, 0, input.availableForRead)
            } else {
                println("READ ERROR: input closed")
                disconnect()
            }
        }
    }

    fun connectAsync() {

        suspend fun createSocket(context: CoroutineContext) = aSocket(ActorSelectorManager(context)).tcp().connect(InetSocketAddress(host, port))

        GlobalScope.launch(ceh + dispatcher) {
            socket = when(port) {
                443 -> createSocket(ceh + dispatcher).tls(ceh + dispatcher)
                else -> createSocket(ceh + dispatcher)
            }

            input = socket.openReadChannel()
            output = socket.openWriteChannel(autoFlush = true)

            connected.set(true)
            println("connected")

            readJob = GlobalScope.async(ceh + dispatcher) {
                readTask(this)
                println("end of read task")
            }
            writeJob = GlobalScope.async(ceh + dispatcher) {
                writeTask(this)
                println("end of write task")
            }
        }

    }

    private fun disconnect() {
        println("disconnect(): ${Thread.currentThread()}")
        connected.set(false)

        readJob.cancel(kotlinx.coroutines.CancellationException("disconnect"))
        writeJob.cancel(kotlinx.coroutines.CancellationException("disconnect"))

        output.close()
        input.cancel()
        socket.close()
    }
}

//This function based on bugfix in Ktor Websocket client: https://github.com/ktorio/ktor/commit/cfd2a841a411e9f4116361e131e0717f38dafe9b
//Original issue: https://github.com/ktorio/ktor/issues/1237
@KtorExperimentalAPI
internal fun fixedThreadPoolDispatcher(
    threadCount: Int,
    threadName: String = "client"
): CoroutineDispatcher {
    val threadsNum = AtomicInteger(0)

    return Executors.newFixedThreadPool(threadCount) {
         Thread(it).apply {
                isDaemon = true
                name = "${threadName}-thread-pool-%d ".format(threadsNum.getAndIncrement())

                //This is the only place where we can catch the exception/error
                setUncaughtExceptionHandler { t: Thread, e: Throwable ->
                    println("UEH -> tread -> ${t.name} | ex: $e")
                }
            }
        }.asCoroutineDispatcher()
}