package app.torboxtv.data

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

sealed class RemoteCommand {
    data object Hello : RemoteCommand()
    data object Disconnect : RemoteCommand()
    data object Browse : RemoteCommand()
    data class Key(val name: String) : RemoteCommand()
    data class Search(val query: String, val sports: Boolean, val client: String, val sequence: Long) : RemoteCommand()
    companion object {
        private val keys = setOf("UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "PLAY", "PAUSE", "TOGGLE", "REWIND", "FORWARD")
        fun parse(raw: String): RemoteCommand {
            val json = JSONObject(raw)
            return when (json.getString("action")) {
                "hello" -> Hello
                "disconnect" -> Disconnect
                "browse" -> Browse
                "key" -> Key(json.getString("key").also { require(it in keys) })
                "search" -> {
                    val query = json.getString("query")
                    val scope = json.getString("scope")
                    val client = json.getString("client")
                    val sequence = json.getLong("sequence")
                    require(query.length <= 160 && query.none { it.code < 32 } && scope in setOf("movies", "sports"))
                    require(client.matches(Regex("[a-zA-Z0-9-]{8,64}")) && sequence in 0..Int.MAX_VALUE.toLong())
                    Search(query, scope == "sports", client, sequence)
                }
                else -> throw IllegalArgumentException("Unknown remote command")
            }
        }
    }
}

/** A bounded, explicitly paired LAN control server. It serves no files, credentials or media. */
class PhoneRemoteServer(private val address: InetAddress, private val prefix: Int, private val page: String,
                        private val dispatch: (RemoteCommand) -> Boolean) : Closeable {
    private val socket = ServerSocket()
    private val listener: Thread
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "TV phone control").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val searches = linkedMapOf<String, Long>()
    private var lastCommandAt = 0L
    private var allowance = 20.0
    @Volatile private var closed = false
    private val createdAt = System.nanoTime()
    val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val origin: String get() = "http://${address.hostAddress}:${socket.localPort}"
    val pairingUrl: String get() = "$origin/#$token"
    val isClosed: Boolean get() = closed

    init {
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress) && prefix in 8..32)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, 0), 8)
        listener = Thread({
            while (!closed) {
                try {
                    val client = socket.accept()
                    if (closed || !sameSubnet(client.inetAddress)) { client.close(); continue }
                    client.soTimeout = 2000
                    clients.add(client)
                    try { workers.execute { client.use { serve(it) }; clients.remove(client) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: IOException) { if (!closed) close() }
            }
        }, "TV phone listener").apply { isDaemon = true }
        listener.start()
    }

    private fun sameSubnet(peer: InetAddress): Boolean {
        if (peer !is Inet4Address) return false
        val local = address.address; val remote = peer.address
        for (bit in 0 until prefix) {
            val mask = 1 shl (7 - bit % 8)
            if ((local[bit / 8].toInt() and mask) != (remote[bit / 8].toInt() and mask)) return false
        }
        return true
    }

    private fun line(input: BufferedInputStream, max: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= max) {
            val value = input.read()
            if (value < 0) throw IOException("Incomplete request")
            if (value == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
            if (value == 0) throw IOException("Invalid header")
            bytes.write(value)
        }
        throw IOException("Request too large")
    }

    private fun serve(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val request = line(input, 1024).split(' ')
            if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) { reply(client, 400); return }
            val headers = linkedMapOf<String, String>()
            var size = 0
            for (index in 0..32) {
                val raw = line(input, 2048); size += raw.length
                if (raw.isEmpty()) break
                if (index == 32 || size > 8192 || ':' !in raw) { reply(client, 400); return }
                val key = raw.substringBefore(':').lowercase()
                if (!key.matches(Regex("[a-z0-9-]+")) || headers.put(key, raw.substringAfter(':').trim()) != null) { reply(client, 400); return }
            }
            if (headers["host"] != origin.removePrefix("http://") || headers.containsKey("transfer-encoding")) { reply(client, 403); return }
            if (System.nanoTime() - createdAt > TimeUnit.HOURS.toNanos(6)) { reply(client, 403); close(); return }
            if (request[0] == "GET" && request[1] == "/") { reply(client, 200, page, "text/html; charset=utf-8"); return }
            if (request[0] != "POST" || request[1] != "/api") { reply(client, 404); return }
            val supplied = headers["x-torbox-remote"].orEmpty()
            if (headers["origin"] != origin || !MessageDigest.isEqual(token.toByteArray(), supplied.toByteArray())) { reply(client, 403); return }
            if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") { reply(client, 400); return }
            val length = headers["content-length"]?.toIntOrNull()
            if (length == null || length !in 1..2048) { reply(client, 413); return }
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) throw IOException("Incomplete body"); read += n }
            val command = try { RemoteCommand.parse(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { reply(client, 400); return }
            synchronized(this) {
                if (closed) return
                val now = System.nanoTime()
                if (lastCommandAt > 0) allowance = (allowance + (now - lastCommandAt) / 1_000_000_000.0 * 10).coerceAtMost(20.0)
                lastCommandAt = now
                if (allowance < 1) { reply(client, 429); return }
                allowance--
                if (command is RemoteCommand.Search) {
                    val previous = searches[command.client]
                    if (previous != null && command.sequence <= previous) { reply(client, 200); return }
                    searches[command.client] = command.sequence
                    while (searches.size > 32) searches.remove(searches.keys.first())
                }
                // Keep request order through the UI acknowledgement, including two
                // phone requests arriving together. Stale search sequences are ignored.
                reply(client, if (dispatch(command)) 200 else 409)
            }
        } catch (_: Exception) { try { reply(client, 400) } catch (_: Exception) {} }
    }

    private fun reply(client: Socket, code: Int, body: String = "{\"ok\":true}", type: String = "application/json") {
        val data = (if (code == 200) body else "{\"ok\":false}").toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code ${if (code == 200) "OK" else "Request rejected"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n"
        client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(data); flush() }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { socket.close() } catch (_: IOException) {}
        clients.toList().forEach { try { it.close() } catch (_: IOException) {} }
        workers.shutdownNow()
        if (Thread.currentThread() !== listener) {
            try { listener.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
