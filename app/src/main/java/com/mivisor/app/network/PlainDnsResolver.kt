package com.mivisor.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * DNS clásico contra un servidor concreto: UDP con EDNS0 y, si la respuesta viene
 * truncada (catálogos grandes), repite por TCP. Así no se pierden datos ni se
 * depende de DNS-over-HTTPS para payloads de varios KB.
 */
class PlainDnsResolver(
    private val server: String,
    private val timeoutMs: Int = 3_500
) : DnsTxtResolver {

    override suspend fun resolveTxt(host: String): List<String> = withContext(Dispatchers.IO) {
        val id = random.nextInt(0x10000)
        val query = DnsMessage.buildTxtQuery(id, host)
        when (val udp = queryUdp(query, id)) {
            is DnsMessage.Result.Records -> udp.txt
            DnsMessage.Result.NotFound -> throw CodeNotFoundException(host)
            DnsMessage.Result.Truncated -> when (val tcp = queryTcp(query, id)) {
                is DnsMessage.Result.Records -> tcp.txt
                DnsMessage.Result.NotFound -> throw CodeNotFoundException(host)
                else -> throw TruncatedResponseException()
            }
            is DnsMessage.Result.Failure -> throw DnsUnavailableException(null)
        }
    }

    private fun CoroutineScope.queryUdp(query: ByteArray, id: Int): DnsMessage.Result {
        val socket = DatagramSocket()
        return closeOnCancel(socket::close) {
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, address, 53))
            val buffer = ByteArray(DnsMessage.EDNS_UDP_SIZE)
            var result: DnsMessage.Result = DnsMessage.Result.Failure(-2)
            // Ignora datagramas ajenos (id distinto) hasta 3 veces.
            for (attempt in 0 until 3) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                result = DnsMessage.parseTxtResponse(buffer, packet.length, id)
                if (!(result is DnsMessage.Result.Failure && result.rcode == -2)) break
            }
            result
        }
    }

    private fun CoroutineScope.queryTcp(query: ByteArray, id: Int): DnsMessage.Result {
        val socket = Socket()
        return closeOnCancel(socket::close) {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(server, 53), timeoutMs)
            val out = socket.getOutputStream()
            out.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            val data = ByteArray(length)
            input.readFully(data)
            DnsMessage.parseTxtResponse(data, length, id)
        }
    }

    private companion object {
        val random = SecureRandom()
    }
}
