package io.github.kunkai2002.splashclean.vpn

/**
 * Minimal IPv4/IPv6 + UDP + DNS handling for a DNS-only VPN (the approach used by DNS66/AdAway):
 * only the fake DNS server address is routed into the tunnel, so every packet we read is a DNS query.
 */
class UdpPacket(
    val isIpv6: Boolean,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
) {
    companion object {
        fun parse(buf: ByteArray, len: Int): UdpPacket? {
            if (len < 20) return null
            val version = (buf[0].toInt() shr 4) and 0xF
            return when (version) {
                4 -> {
                    val ihl = (buf[0].toInt() and 0xF) * 4
                    if (buf[9].toInt() != 17 || len < ihl + 8) return null // UDP only
                    val src = buf.copyOfRange(12, 16)
                    val dst = buf.copyOfRange(16, 20)
                    parseUdp(buf, ihl, len, false, src, dst)
                }

                6 -> {
                    if (len < 48 || buf[6].toInt() != 17) return null // no extension headers expected
                    val src = buf.copyOfRange(8, 24)
                    val dst = buf.copyOfRange(24, 40)
                    parseUdp(buf, 40, len, true, src, dst)
                }

                else -> null
            }
        }

        private fun parseUdp(buf: ByteArray, off: Int, len: Int, v6: Boolean, src: ByteArray, dst: ByteArray): UdpPacket? {
            val sp = u16(buf, off)
            val dp = u16(buf, off + 2)
            val ulen = u16(buf, off + 4)
            val end = minOf(len, off + ulen)
            if (end < off + 8) return null
            return UdpPacket(v6, src, dst, sp, dp, buf.copyOfRange(off + 8, end))
        }

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        private fun checksum(data: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
            var sum = initial
            var i = off
            while (i + 1 < off + len) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < off + len) sum += (data[i].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }

        private fun pseudoSum(src: ByteArray, dst: ByteArray, udpLen: Int): Long {
            var sum = 0L
            for (a in listOf(src, dst)) {
                var i = 0
                while (i < a.size) {
                    sum += ((a[i].toInt() and 0xFF) shl 8) or (a[i + 1].toInt() and 0xFF)
                    i += 2
                }
            }
            return sum + 17 + udpLen
        }
    }

    /** Builds the reply packet (addresses and ports swapped) carrying [dns]. */
    fun reply(dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val ipLen = if (isIpv6) 40 else 20
        val out = ByteArray(ipLen + udpLen)
        if (isIpv6) {
            out[0] = 0x60
            out[4] = (udpLen shr 8).toByte(); out[5] = udpLen.toByte()
            out[6] = 17; out[7] = 64
            System.arraycopy(dstAddr, 0, out, 8, 16)
            System.arraycopy(srcAddr, 0, out, 24, 16)
        } else {
            val total = ipLen + udpLen
            out[0] = 0x45
            out[2] = (total shr 8).toByte(); out[3] = total.toByte()
            out[6] = 0x40 // don't fragment
            out[8] = 64; out[9] = 17
            System.arraycopy(dstAddr, 0, out, 12, 4)
            System.arraycopy(srcAddr, 0, out, 16, 4)
            val c = checksum(out, 0, 20)
            out[10] = (c shr 8).toByte(); out[11] = c.toByte()
        }
        val u = ipLen
        out[u] = (dstPort shr 8).toByte(); out[u + 1] = dstPort.toByte()
        out[u + 2] = (srcPort shr 8).toByte(); out[u + 3] = srcPort.toByte()
        out[u + 4] = (udpLen shr 8).toByte(); out[u + 5] = udpLen.toByte()
        System.arraycopy(dns, 0, out, u + 8, dns.size)
        var c = checksum(out, u, udpLen, pseudoSum(dstAddr, srcAddr, udpLen))
        if (c == 0) c = 0xFFFF
        out[u + 6] = (c shr 8).toByte(); out[u + 7] = c.toByte()
        return out
    }
}

object Dns {
    data class Question(val name: String, val type: Int, val end: Int)

    /** Reads the first question of a DNS query. */
    fun question(msg: ByteArray): Question? {
        if (msg.size < 12) return null
        val qd = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        if (qd < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (i < msg.size) {
            val l = msg[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0) return null // no compression in a question
            if (i + 1 + l > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((msg[i + k].toInt() and 0xFF).toChar())
            i += 1 + l
        }
        if (i + 4 > msg.size) return null
        val type = ((msg[i].toInt() and 0xFF) shl 8) or (msg[i + 1].toInt() and 0xFF)
        return Question(sb.toString().lowercase(), type, i + 4)
    }

    /** Answers A with 0.0.0.0, AAAA with ::, anything else with an empty NOERROR. */
    fun blockedResponse(query: ByteArray, q: Question): ByteArray {
        val header = query.copyOfRange(0, 12)
        header[2] = (0x80 or (query[2].toInt() and 0x01)).toByte() // QR=1, keep RD
        header[3] = 0x80.toByte() // RA=1, RCODE=0
        header[4] = 0; header[5] = 1 // QDCOUNT=1
        header[8] = 0; header[9] = 0 // NSCOUNT
        header[10] = 0; header[11] = 0 // ARCOUNT
        val question = query.copyOfRange(12, q.end)
        val rdata = when (q.type) {
            1 -> ByteArray(4)
            28 -> ByteArray(16)
            else -> null
        }
        header[6] = 0; header[7] = if (rdata != null) 1 else 0 // ANCOUNT
        if (rdata == null) return header + question
        val answer = byteArrayOf(
            0xC0.toByte(), 12, // pointer to the question name
            (q.type shr 8).toByte(), q.type.toByte(),
            0, 1, // class IN
            0, 0, 0, 60, // TTL 60 s
            0, rdata.size.toByte(),
        ) + rdata
        return header + question + answer
    }
}
