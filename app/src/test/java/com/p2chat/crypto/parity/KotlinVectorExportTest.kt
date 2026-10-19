package com.p2chat.crypto.parity

import com.p2chat.crypto.envelope.EnvelopeBuilder
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.crypto.prim.AesGcm
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import com.p2chat.wire.WireCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KotlinVectorExportTest {

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun exportKotlinVectors_forCrossLanguageVerification() {
        val vectorsArray = JSONArray()
        val baseSentAtMs = 1792500000000L

        val testCases = listOf(
            Pair(PacketType.CHAT, ""),
            Pair(PacketType.CHAT, "A"),
            Pair(PacketType.CHAT, "Hello from Kotlin!"),
            Pair(PacketType.CHAT, "Unicode: \uD83D\uDEE1\uFE0F Mesh \uD83D\uDD12 Crypto \uD83D\uDCF6 BLE"),
            Pair(PacketType.CHAT, "Accents: café, déjà vu, façade, naïve, Schön"),
            Pair(PacketType.CHAT, "Cyrillic: Привет мир! Свобода и связь."),
            Pair(PacketType.CHAT, "East Asian: サーバーレス・暗号化・メッシュ通信"),
            Pair(PacketType.CHAT, "Symbols & Numbers: 12:00 PM, $100, 100%, user@example.com, #tactical!"),
            Pair(PacketType.CHAT, "Medium paragraph text ".repeat(5).trim()),
            Pair(PacketType.CHAT, "X".repeat(350)), // Max 350 bytes
            Pair(PacketType.KEY_ROTATE, "Rotate key payload"),
            Pair(PacketType.ROSTER_SYNC, "Roster sync payload"),
            Pair(PacketType.PAIRWISE_INVITE, "Pairwise invite payload"),
            Pair(PacketType.BENCH_DATA, "Benchmark data payload 1"),
            Pair(PacketType.BENCH_ACK, "Benchmark ack payload 1"),
            Pair(PacketType.CHAT, "TTL 1 test: last hop delivery"),
            Pair(PacketType.CHAT, "TTL 15 test: maximum allowed hop count"),
            Pair(PacketType.CHAT, "Repeated chars: aa bb ccc dddd eeeee"),
            Pair(PacketType.CHAT, "Multi-byte: " + "\uD83D\uDE00".repeat(30)),
            Pair(PacketType.CHAT, "Whitespace test: \n\t  line1\nline2  \t"),
            Pair(PacketType.CHAT, "Deterministic vector 21"),
            Pair(PacketType.CHAT, "Deterministic vector 22")
        )

        assertTrue("Must generate at least 20 positive vectors", testCases.size >= 20)

        for ((index, testCase) in testCases.withIndex()) {
            val (packetType, plaintext) = testCase
            val keyPair = SoftwareEcProvider.generateKeyPair()
            val pubBytes = EcProvider.encodeUncompressedPublicKey(keyPair.public)
            val senderFp = EcProvider.computeSenderFp(pubBytes)

            val groupKey = ByteArray(32) { ((it + index * 7) and 0xFF).toByte() }
            val nonce = ByteArray(12) { ((it + index * 11) and 0xFF).toByte() }

            val ttl = when {
                plaintext.startsWith("TTL 1 test") -> 1
                plaintext.startsWith("TTL 15 test") -> 15
                else -> 7
            }

            val msgId = 0x1000000000000000L + index
            val groupId = 1L + (index % 5)
            val keyEpoch = 1 + (index % 3)
            val sentAtMs = baseSentAtMs + (index * 1000L)

            val header = PacketHeader(
                packetType = packetType,
                ttl = ttl,
                msgId = msgId,
                groupId = groupId,
                senderFp = senderFp,
                keyEpoch = keyEpoch
            )

            val frameBytes = EnvelopeBuilder.buildChatPacket(
                header = header,
                sentAtMs = sentAtMs,
                plaintext = plaintext,
                groupKey = groupKey,
                signingPrivateKey = keyPair.private,
                explicitNonce = nonce
            )

            val wireFrame = WireCodec.decode(frameBytes).getOrThrow()

            val vectorObj = JSONObject().apply {
                put("index", index)
                put("description", "Kotlin Vector ${index + 1}: ${packetType.name}")
                put("packet_type", packetType.code.toInt())
                put("ttl", ttl)
                put("msg_id_hex", "%016x".format(msgId))
                put("group_id_hex", "%08x".format(groupId))
                put("sender_fp_hex", EcProvider.senderFpHex(senderFp))
                put("key_epoch", keyEpoch)
                put("signing_pubkey_uncompressed_hex", bytesToHex(pubBytes))
                put("group_key_hex", bytesToHex(groupKey))
                put("nonce_hex", bytesToHex(wireFrame.coreBlob.nonce))
                put("tag_hex", bytesToHex(wireFrame.coreBlob.tag))
                put("ciphertext_len", wireFrame.coreBlob.ciphertext.size)
                put("ciphertext_hex", bytesToHex(wireFrame.coreBlob.ciphertext))
                put("signature_hex", bytesToHex(wireFrame.signature))
                put("aad_hex", bytesToHex(header.computeAad()))
                put("sent_at_ms", sentAtMs)
                put("plaintext", plaintext)
                put("frame_hex", bytesToHex(frameBytes))
                put("frame_len", frameBytes.size)
            }
            vectorsArray.put(vectorObj)
        }

        val rootObj = JSONObject().apply {
            put("version", "1.0")
            put("generator", "KotlinVectorExportTest")
            put("count", vectorsArray.length())
            put("vectors", vectorsArray)
        }

        // Write to build directory
        val buildDir = File("build").apply { mkdirs() }
        val outputFile = File(buildDir, "kotlin_vectors.json")
        outputFile.writeText(rootObj.toString(2), Charsets.UTF_8)

        println("Exported ${vectorsArray.length()} Kotlin vectors to ${outputFile.absolutePath}")
        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }
}
