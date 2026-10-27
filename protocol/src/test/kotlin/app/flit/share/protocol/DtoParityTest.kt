package app.flit.share.protocol

import app.flit.share.protocol.model.AnnounceMessage
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.tls.TlsIdentity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-format parity with the official (Rust/Dart) implementation, asserted
 * against the test JSON from localsend/localsend's `packages/core/src/model/discovery.rs`.
 */
class DtoParityTest {

    @Test
    fun `announce serializes like upstream rust test fixture`() {
        val msg = AnnounceMessage(
            alias = "Nice Orange",
            version = "2.2",
            deviceModel = "Samsung",
            deviceType = "mobile",
            fingerprint = "random string",
            port = 53317,
            protocol = "https",
            download = true,
        )
        val json = WireJson.encode(msg)
        assertTrue(json.contains("\"alias\":\"Nice Orange\""), json)
        assertTrue(json.contains("\"version\":\"2.2\""), json)
        assertTrue(json.contains("\"fingerprint\":\"random string\""), json)
        assertTrue(json.contains("\"port\":53317"), json)
        assertTrue(json.contains("\"protocol\":\"https\""), json)
        assertTrue(json.contains("\"deviceType\":\"mobile\""), json)
        assertTrue(json.contains("\"download\":true"), json)
        assertFalse(json.contains("announce"), "modern v2.2 announcements never carry the legacy announce flag: $json")
    }

    @Test
    fun `announce parses upstream fixture and tolerates missing optionals`() {
        val json = """
            {"alias":"Secret Banana","version":"2.2","deviceModel":"Windows","deviceType":"desktop",
             "fingerprint":"random string","port":53317,"protocol":"https","download":true}
        """.trimIndent()
        val msg = WireJson.decode<PeerInfo>(json)
        assertEquals("Secret Banana", msg.alias)
        assertEquals("2.2", msg.version)
        assertEquals("Windows", msg.deviceModel)
        assertEquals("desktop", msg.deviceType)
        assertEquals(53317, msg.port)
        assertEquals("https", msg.protocol)
        assertTrue(msg.download)
    }

    @Test
    fun `legacy v2_0 announce with announce field still parses`() {
        val json = """
            {"alias":"Old Phone","version":"2.0","fingerprint":"abc","port":53317,
             "protocol":"http","download":false,"announce":true}
        """.trimIndent()
        val msg = WireJson.decode<PeerInfo>(json)
        assertEquals("Old Phone", msg.alias)
        assertTrue(msg.announce == true)
    }

    @Test
    fun `register info always carries port and protocol like upstream DTO`() {
        // Upstream RegisterDtoV2 (rust, serde) declares `port` and `protocol` with
        // NO defaults, so omitting their values fails strict readers (the real CLI
        // answered "Invalid JSON body"). Extra fields are ignored by serde readers.
        val self = PeerInfo(
            alias = "Flit",
            version = "2.2",
            fingerprint = "FP",
            port = Protocol.DEFAULT_PORT,
            protocol = "https",
        )
        val json = WireJson.encode(self.sansAnnounce())
        assertTrue(json.contains("\"port\":53317"), json)
        assertTrue(json.contains("\"protocol\":\"https\""), json)
        assertFalse(json.contains("\"announce\""), json)
    }

    @Test
    fun `prepare upload roundtrip matches spec sample`() {
        val specJson = """
            {"info":{"alias":"Nice Orange","version":"2.0","deviceModel":"Samsung","deviceType":"mobile",
             "fingerprint":"random string","port":53317,"protocol":"https","download":true},
             "files":{"some file id":{
               "id":"some file id","fileName":"my image.png","size":324242,"fileType":"image/jpeg",
               "sha256":"deadbeef",
               "metadata":{"modified":"2021-01-01T12:34:56Z"}
             }}}
        """.trimIndent()
        val req = WireJson.decode<PrepareUploadRequest>(specJson)
        assertEquals("my image.png", req.files["some file id"]?.fileName)
        assertEquals(324242L, req.files["some file id"]?.size)
        assertEquals("2021-01-01T12:34:56Z", req.files["some file id"]?.metadata?.modified)
        val again = WireJson.encode(req)
        assertTrue(again.contains("\"fileName\":\"my image.png\""), again)
    }

    @Test
    fun `prepare upload response stays v2_2-shaped without multi socket`() {
        val json = app.flit.share.protocol.model.PrepareUploadResponse(sessionId = "s", files = mapOf("f1" to "t"))
        val encoded = WireJson.encode(json)
        assertFalse(encoded.contains("multipart"), "v2.2 answers must never grow extra fields: $encoded")
        assertTrue(encoded.contains("\"sessionId\":\"s\""), encoded)
        assertTrue(encoded.contains("\"files\":{\"f1\":\"t\"}"), encoded)
    }

    @Test
    fun `multi socket response carries the chunk hint and decodes back`() {
        val json = app.flit.share.protocol.model.PrepareUploadResponse(
            sessionId = "s", files = mapOf("f1" to "t"), multipart = ServerCore.TURBO_CHUNK_BYTES,
        )
        val encoded = WireJson.encode(json)
        assertTrue(encoded.contains("\"multipart\":${ServerCore.TURBO_CHUNK_BYTES}"), encoded)
        val back = WireJson.decode<app.flit.share.protocol.model.PrepareUploadResponse>(encoded)
        assertEquals(ServerCore.TURBO_CHUNK_BYTES, back.multipart)
    }

    @Test
    fun `v2_2 receivers tolerate a response that carries the hint`() {
        // Flit receive + plain v2.2 senders: they parse OUR response DTOs leniently,
        // and we must equally survive a peer that answers with the hint.
        val json = """{"sessionId":"x","files":{"a":"b"},"multipart":8388608}"""
        val back = WireJson.decode<app.flit.share.protocol.model.PrepareUploadResponse>(json)
        assertEquals(8388608L, back.multipart)
        assertEquals("x", back.sessionId)
    }
}

class TlsIdentityTest {

    @Test
    fun `fingerprint is uppercase hex sha256 of certificate`() {
        val identity = TlsIdentity.generate()
        assertEquals(64, identity.fingerprint.length)
        assertTrue(identity.fingerprint.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `keystore survival roundtrip keeps fingerprint`() {
        val dir = createTempDirectory("flit-ids")
        val file = File(dir.toFile(), "identity.p12")
        val identity = TlsIdentity.generate()
        identity.save(file)
        val reloaded = TlsIdentity.load(file)
        assertEquals(identity.fingerprint, reloaded.fingerprint)
    }
}
