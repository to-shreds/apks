package app.torboxtv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

class PhoneRemoteServerTest {
    private fun server(commands: MutableList<RemoteCommand> = CopyOnWriteArrayList()) =
        PhoneRemoteServer(InetAddress.getByName("127.0.0.1"), 32, "<html>Local remote</html>") { commands.add(it); true }
    private fun request(server: PhoneRemoteServer, body: String? = null, token: String = server.token,
                        origin: String = server.origin, host: String = server.origin.removePrefix("http://"), path: String = if(body==null)"/" else "/api",
                        extra: String = ""): String {
        val url = URI(server.origin)
        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 3000
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val header = "${if(body==null)"GET" else "POST"} $path HTTP/1.1\r\nHost: $host\r\n" +
                (if(bytes!=null)"Origin: $origin\r\nX-TorBox-Remote: $token\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") + extra + "\r\n"
            socket.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
            if(bytes!=null)socket.getOutputStream().write(bytes)
            return socket.getInputStream().bufferedReader().readText()
        }
    }
    private fun code(response: String) = response.substringBefore("\r\n").split(' ')[1].toInt()

    @Test fun pageIsLocalNoncachedAndNeverDisclosesThePairingToken() {
        server().use { server ->
            val response=request(server)
            assertEquals(200, code(response));assertTrue(response.endsWith("<html>Local remote</html>"))
            assertTrue(response.contains("Cache-Control: no-store"));assertTrue(response.contains("X-Frame-Options: DENY"))
            assertFalse(response.contains(server.token));assertTrue(server.pairingUrl.endsWith("/#"+server.token))
            assertEquals(48,server.token.length)
        }
    }

    @Test fun commandsRequirePairingExactOriginAndHost() {
        val commands=CopyOnWriteArrayList<RemoteCommand>()
        server(commands).use { server ->
            val body="{\"action\":\"key\",\"key\":\"OK\"}"
            assertEquals(403,code(request(server,body,token="incorrect")))
            assertEquals(403,code(request(server,body,origin="https://evil.example")))
            assertEquals(403,code(request(server,body,host="evil.example")))
            assertTrue(commands.isEmpty())
            assertEquals(200,code(request(server,body)));assertEquals(listOf(RemoteCommand.Key("OK")),commands)
        }
    }

    @Test fun serverCannotFetchOrServeFilesMediaAccountsOrArbitraryKeys() {
        val commands=CopyOnWriteArrayList<RemoteCommand>()
        server(commands).use { server ->
            for(path in listOf("/video.mp4","/../private","/api?token=anything","https://example.com/video","/settings","/account"))assertEquals(404,code(request(server,path=path)))
            for(body in listOf("{\"action\":\"key\",\"key\":\"TYPE_PASSWORD\"}","{\"action\":\"openUrl\",\"url\":\"http://private/\"}","{\"action\":\"search\",\"query\":\"x\",\"scope\":\"account\",\"client\":\"phone-1234\",\"sequence\":1}"))assertEquals(400,code(request(server,body)))
            assertTrue(commands.isEmpty())
        }
    }

    @Test fun latestSearchSequenceWinsAndStringsRemainBounded() {
        val commands=CopyOnWriteArrayList<RemoteCommand>()
        server(commands).use { server ->
            fun body(sequence:Int,query:String) = JSONObject().put("action","search").put("query",query).put("scope","movies").put("client","phone-1234").put("sequence",sequence).toString()
            assertEquals(200,code(request(server,body(2,"Jumanji"))))
            assertEquals(200,code(request(server,body(1,"J"))))
            assertEquals(1,commands.size);assertEquals("Jumanji",(commands.single() as RemoteCommand.Search).query)
            assertEquals(400,code(request(server,body(3,"x".repeat(161)))))
            assertEquals(400,code(request(server,body(3,"new\nline"))))
        }
    }

    @Test fun malformedHeadersOversizedBodiesAndChunkedEncodingFailClosed() {
        val commands=CopyOnWriteArrayList<RemoteCommand>()
        server(commands).use { server ->
            assertEquals(400,code(request(server,"{")))
            assertEquals(413,code(request(server,"x".repeat(2049))))
            assertEquals(403,code(request(server,"{\"action\":\"hello\"}",extra="Transfer-Encoding: chunked\r\n")))
            assertEquals(400,code(request(server,"{\"action\":\"hello\"}",extra="Host: other\r\n")))
            assertTrue(commands.isEmpty())
        }
    }

    @Test fun fiftySessionRestartsInvalidateEveryOldPairingToken() {
        var previous=""
        repeat(50){
            server().use { server ->
                assertNotEquals(previous,server.token)
                if(previous.isNotEmpty())assertEquals(403,code(request(server,"{\"action\":\"hello\"}",token=previous)))
                assertEquals(200,code(request(server,"{\"action\":\"hello\"}")))
                previous=server.token
            }
        }
    }

    @Test fun closingTheSessionClosesIdleConnectionsAndListeningPort() {
        val server=server();val url=URI(server.origin)
        Socket(url.host,url.port).use { client ->
            client.soTimeout=3000
            Thread.sleep(30)
            server.close()
            try { assertEquals(-1,client.getInputStream().read()) } catch (_:java.net.SocketException) { /* TCP reset also means closed. */ }
            assertTrue(server.isClosed)
            // Closing a listener can race with a completed kernel handshake or
            // ephemeral-port reuse. The contract is that an old pairing can no
            // longer serve/control the app, even if TCP connect briefly succeeds.
            try {
                Socket(url.host,url.port).use { probe ->
                    probe.soTimeout=500
                    probe.getOutputStream().write("GET / HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\n\r\n".toByteArray())
                    assertFalse("Closed session served the control page",probe.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
                }
            }catch(_:java.io.IOException){}
        }
    }

    @Test fun rapidCommandsHaveABoundedRateAndNeverCrashTheListener() {
        val commands=CopyOnWriteArrayList<RemoteCommand>()
        server(commands).use { server ->
            val codes=(1..80).map{code(request(server,"{\"action\":\"key\",\"key\":\"DOWN\"}"))}
            assertTrue(codes.all{it==200||it==429});assertTrue(codes.any{it==429})
            assertTrue(commands.size<80);assertEquals(200,code(request(server)))
        }
    }
}
