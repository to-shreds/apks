package app.torboxtv.data

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64

/** Capabilities learned only from this game's exact, already-verified HTTPS master. */
class BuffStreamsPlaybackScope(val id: String, val master: String) {
    init { require(Regex("[0-9]{1,9}").matches(id) && BuffStreamsPolicy.validMaster(master,id)) }
    private data class Routes(val variants: Set<String>, val segments: Set<String>)
    @Volatile private var routes = Routes(emptySet(),emptySet())
    private val encodedId = Base64.getEncoder().encodeToString(id.toByteArray(Charsets.US_ASCII))
    private fun plain(u: HttpUrl) = u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null
    private fun playlistHost(host: String) = host == BuffStreamsPolicy.MASTER_HOST || Regex("pl\\.[a-z0-9](?:[a-z0-9-]{0,58}[a-z0-9])?\\.space").matches(host)
    private fun segmentHost(host: String) = Regex("[ab]\\.[a-z0-9](?:[a-z0-9-]{0,58}[a-z0-9])?\\.space").matches(host)
    fun allows(value: String): Boolean = try {
        val u = value.toHttpUrl()
        val current = routes
        plain(u) && (u.toString() == master || u.toString() in current.variants ||
            (u.host in current.segments && Regex("/scripts/"+Regex.escape(encodedId)+"/p[0-9]{1,24}_[0-9]{1,12}\\.txt").matches(u.encodedPath)))
    } catch (_: Exception) { false }
    fun validReferer(value: String?) = value == BuffStreamsPolicy.embed(id)
    /** Replace atomically, never accumulate hosts across master refreshes or other games. */
    fun acceptMaster(raw: String) {
        if (raw.toByteArray(Charsets.UTF_8).size > 256*1024 || raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } != "#EXTM3U") throw SportsManifestRejected()
        val variants = linkedSetOf<String>()
        val segments = linkedSetOf<String>()
        var expectingVariant = false
        for (line in raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (Regex("(?i)\\bURI\\s*=").containsMatchIn(line)) throw SportsManifestRejected()
            if (line.startsWith("#EXT-X-STREAM-INF:")) { if (expectingVariant) throw SportsManifestRejected(); expectingVariant=true; continue }
            if (line.startsWith("#")) continue
            if (!expectingVariant || variants.size >= 12) throw SportsManifestRejected()
            val u = try { line.toHttpUrl() } catch (_: Exception) { throw SportsManifestRejected() }
            val path=u.pathSegments
            if (!plain(u) || !playlistHost(u.host) || path.size != 4 || path[0] != "playlist" || path[1] != id || path[3] != "caxi" || !segmentHost(path[2]) || u.encodedPath != "/playlist/$id/${path[2]}/caxi") throw SportsDestinationRejected()
            variants.add(u.toString());segments.add(path[2]);expectingVariant=false
        }
        if (expectingVariant || variants.isEmpty()) throw SportsManifestRejected()
        routes=Routes(variants.toSet(),segments.toSet())
    }
}

class SportsManifestRejected : IOException("The sports provider returned an unsupported playlist.")

/** Hostnames authorized by a manifest must still resolve exclusively to public addresses. */
class SportsPublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses=delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("Sports destination is not public")
        return addresses
    }
    companion object {
        internal fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
            val b=a.address.map { it.toInt() and 255 }
            return if (b.size==4) {
                !(b[0]==0 || b[0]==10 || b[0]==127 || b[0]>=224 || (b[0]==100 && b[1] in 64..127) ||
                    (b[0]==169 && b[1]==254) || (b[0]==172 && b[1] in 16..31) || (b[0]==192 && b[1]==168) || (b[0]==192 && b[1]==88 && b[2]==99) ||
                    (b[0]==192 && b[1]==0 && b[2] in setOf(0,2)) || (b[0]==198 && b[1] in setOf(18,19)) ||
                    (b[0]==198 && b[1]==51 && b[2]==100) || (b[0]==203 && b[1]==0 && b[2]==113))
            } else {
                // Permit only global unicast, excluding documentation, Teredo and transition routes.
                b.size==16 && (b[0] and 224)==32 && !(b[0]==32 && b[1]==1 &&
                    ((b[2]==13 && b[3]==184) || (b[2]==0 && b[3] in 0..47))) &&
                    !(b[0]==32 && b[1]==2) && !(b[0]==63 && b[1]==255 && (b[2] and 240)==0)
            }
        }
    }
}
