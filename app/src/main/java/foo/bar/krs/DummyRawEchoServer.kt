package foo.bar.krs

import java.io.IOException
import java.lang.Exception
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

fun Socket.isActive() = !isClosed || !isInputShutdown || !isOutputShutdown

class DummyRawEchoServer(private val port: Int = 8765) {
    fun start() {
        thread {
            try {
                ServerSocket(port).use { serverSocket ->
                    println("server is listening on port $port")
                    while (!serverSocket.isClosed) {
                        val socket = serverSocket.accept()
                        println("new client connected")

                        thread {
                            val output = socket.getOutputStream()
                            val input = socket.getInputStream()
                            while (socket.isActive()) {
                                try {
                                    output.write(input.read())
                                } catch (cause: Exception) {
                                    println("server error: $cause")
                                    serverSocket.close()
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (cause: IOException) {
                println("server exception: $cause")
                cause.printStackTrace()
            }
        }
    }
}