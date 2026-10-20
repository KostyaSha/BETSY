package org.betsy.decode

import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import org.betsy.model.InfDetail

/**
 * PROTOCOL.md §7.4.2, reading the INF sub-code out of a freeze page.
 *
 * `21C6`..`21CA` on `7E2` are **per-DTC freeze pages**, not flag tables. When the ECU stores a DTC
 * it writes one page, and the page carries the sub-code as a `u16` big-endian at **bytes 29-30**
 * of the payload, alongside a frame of analog snapshot values.
 *
 * So decoding is a field read, not a search:
 *
 * ```
 * page not all zero  ->  sub-code = u16BE(payload[29], payload[30])
 * ```
 *
 * Measured on a 2009 Gen2 with `P0571` stored: bytes 29-30 read `0x0073`, decimal 115, which is
 * that code's documented sub-code. All 48 bytes remained identical across an ignition cycle, so a
 * page is a snapshot written when the fault sets rather than live data.
 *
 * **Why there is no bit map here.** An earlier model treated each page as a table of flags and
 * called a slot active when non-zero. On a car whose only fault was a brake switch that reported
 * **35 simultaneous sub-codes**: those bytes are analog readings in offset binary, which is why a
 * car at rest shows a run of `0x80` midpoints. `InfLayout` still describes those 62 analog items
 * for naming and scaling, but it plays no part in reading the sub-code.
 */
object InfDecoder {
    /** Byte offset of the sub-code field within a page payload; 16 bits, big-endian. */
    const val CODE_BYTE = 29

    /** Complete payload size of the supported Gen2 compact freeze-page layout. */
    private const val PAGE_BYTES = 48

    /**
     * The sub-code carried by one complete page, or an empty list when it carries none.
     *
     * An all-zero page or a zero detail field yields no entry. Nonzero unsigned 16-bit
     * values are retained even when their meaning is unknown; interpretation is separate.
     * Incomplete pages and missing `61 <lid>` tags throw because the read failed (§2.2).
     * A length-prefixed response must contain every declared byte before padding is ignored.
     */
    fun decodeActive(
        r: String,
        table: InfTable,
    ): List<InfDetail> {
        val i = Normalize.requireTag(r, table.tag)
        val payloadStart = i + 4
        var bytes = (r.length - payloadStart) / 2

        // ELM multi-frame output can prefix the tag with a three-hex-digit application length,
        // including the service and local identifier. Require the whole declared response.
        if (i > 0) {
            val declared = if (i == 3) r.substring(0, i).toIntOrNull(16) else null
            if (declared == null || declared < 2) {
                throw NoDataException("${table.request}: invalid response length prefix")
            }
            Normalize.requireLength(r, i + declared * 2, table.request)
            bytes = declared - 2
        }
        if (bytes < PAGE_BYTES) {
            throw NoDataException("${table.request}: truncated INF page ($bytes < $PAGE_BYTES payload bytes)")
        }

        val payload = Normalize.bytes(r, i + 4, bytes)
        if (payload.all { it.toInt() == 0 }) return emptyList()

        val code =
            ((payload[CODE_BYTE].toInt() and 0xFF) shl 8) or
                (payload[CODE_BYTE + 1].toInt() and 0xFF)
        if (code == 0) return emptyList()
        return listOf(InfDetail(table = table.lid, code = code))
    }
}
