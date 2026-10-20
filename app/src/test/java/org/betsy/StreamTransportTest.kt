package org.betsy

import org.betsy.elm.ElmSession
import org.betsy.transport.StreamTransport
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class StreamTransportTest {
    private fun transport(
        reply: String,
        failure: IOException? = null,
        sent: ByteArrayOutputStream = ByteArrayOutputStream(),
    ): StreamTransport =
        object : StreamTransport() {
            override val input =
                object : InputStream() {
                    private var index = 0

                    override fun read(): Int {
                        if (index < reply.length) return reply[index++].code
                        if (failure != null) throw failure
                        return -1
                    }
                }
            override val output = sent

            override fun applyTimeout(ms: Int) {}

            override fun close() {}
        }

    @Test
    fun failuresRetainOnlyBytesFromTheirOwnUnfinishedExchange() {
        val partial = "032\r0:61C78080\r"
        for (failure in listOf(null, SocketTimeoutException("late prompt"), IOException("connection reset"))) {
            val sent = ByteArrayOutputStream()
            val session = ElmSession(transport(partial, failure, sent))
            val error = assertThrows(TransportException::class.java) { awaitBlocking { session.rawCommand("21C7") } }
            assertEquals(partial, error.partialResponse)
            assertEquals(partial, session.lastRawResponse)
            assertEquals("21C7\r", sent.toString("US-ASCII"))
            assertSame(failure, error.cause)
        }
    }

    @Test
    fun anEmptyFailedExchangeDoesNotInventRawEvidence() {
        val session = ElmSession(transport("", SocketTimeoutException("no bytes")))
        val error = assertThrows(TransportException::class.java) { awaitBlocking { session.rawCommand("21C7") } }
        assertEquals("", error.partialResponse)
        assertEquals("", session.lastRawResponse)
    }

    @Test
    fun aPromptStillTerminatesACompleteReplyNormally() {
        val raw = "61C70000\r"
        val session = ElmSession(transport(raw + ">"))
        assertEquals(raw, awaitBlocking { session.rawCommand("21C7") })
        assertEquals(raw, session.lastRawResponse)
    }
}
