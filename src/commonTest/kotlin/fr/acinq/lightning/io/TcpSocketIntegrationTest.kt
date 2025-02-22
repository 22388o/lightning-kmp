package fr.acinq.lightning.io

import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import io.ktor.utils.io.core.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@OptIn(ExperimentalTime::class)
class TcpSocketIntegrationTest : LightningTestSuite() {

    private val serverVersionRpc = buildString {
        append("""{ "id":"0", "method":"server.version", "params": ["3.3.6", "1.4"]}""")
        appendLine()
    }.toByteArray()

    @Test
    fun `TCP connection IntegrationTest`() = runSuspendTest(timeout = Duration.seconds(250)) {
        val socket = TcpSocket.Builder().connect("localhost", 51001)
        socket.send(serverVersionRpc)
        val ba = ByteArray(8192)
        val size = socket.receiveAvailable(ba)
        assertTrue { size > 0 }
        socket.close()
    }

    @Test
    fun `SSL connection`() = runSuspendTest(timeout = Duration.seconds(5)) {
        val socket = TcpSocket.Builder().connect("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES)
        socket.send(serverVersionRpc)
        val size = socket.receiveAvailable(ByteArray(8192))
        assertTrue { size > 0 }
        socket.close()
    }
}