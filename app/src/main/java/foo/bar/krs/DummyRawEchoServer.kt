package foo.bar.krs

import java.io.IOException
import java.lang.Exception
import java.net.ServerSocket
import kotlin.concurrent.thread

class DummyRawEchoServer(private val port: Int = 8765) {
    fun start() {
        thread {
            try {
                ServerSocket(port).use { serverSocket ->
                    println("Server is listening on port $port")
                    while (true) {
                        val socket = serverSocket.accept()
                        println("New client connected")

                        thread {
                            val output = socket.getOutputStream()
                            val input = socket.getInputStream()
                            while (!socket.isClosed || !socket.isInputShutdown || !socket.isOutputShutdown) {
                                try {
                                    output.write(input.read())
                                } catch (cause: Exception) {

                                }
                            }
                        }
                    }
                }
            } catch (ex: IOException) {
                println("Server exception: " + ex.message)
                ex.printStackTrace()
            }
        }
    }
}