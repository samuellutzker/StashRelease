package com.stashapp.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire format against drift from the Rust server (`server/src/protocol.rs`):
 * exact snake_case keys, message `type` discriminators, and round-tripping.
 */
class ProtocolTest {

    private fun sampleItem() = SyncItem(
        id = "id-1", title = "Title", content = "https://example.com", type = "link",
        mimeType = "text/plain", isPinned = true, createdAt = 111, updatedAt = 222,
        hasFile = false, fileHash = "abc", thumbnailB64 = ""
    )

    @Test fun syncItemUsesSnakeCaseKeys() {
        val o = sampleItem().toJson()
        // These exact keys must match what the server's serde expects.
        for (k in listOf("id", "title", "content", "type", "mime_type", "is_pinned",
                "created_at", "updated_at", "has_file", "file_hash")) {
            assertTrue("missing key $k", o.has(k))
        }
    }

    @Test fun syncItemRoundTrips() {
        val original = sampleItem()
        val back = SyncItem.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, back)
    }

    @Test fun parsesChallenge() {
        val m = ServerMsg.parse("""{"type":"challenge","nonce":"N"}""")
        assertTrue(m is ServerMsg.Challenge)
        assertEquals("N", (m as ServerMsg.Challenge).nonce)
    }

    @Test fun parsesAuthResults() {
        assertTrue(ServerMsg.parse("""{"type":"auth_ok"}""") is ServerMsg.AuthOk)
        assertTrue(ServerMsg.parse("""{"type":"auth_fail"}""") is ServerMsg.AuthFail)
    }

    @Test fun parsesItemAddedAndDeleted() {
        val added = ServerMsg.parse("""{"type":"item_added","item":${sampleItem().toJson()}}""")
        assertTrue(added is ServerMsg.ItemAdded)
        assertEquals("id-1", (added as ServerMsg.ItemAdded).item.id)

        val del = ServerMsg.parse("""{"type":"item_deleted","id":"x"}""")
        assertEquals("x", (del as ServerMsg.ItemDeleted).id)
    }

    @Test fun parsesFileMessages() {
        val chunk = ServerMsg.parse("""{"type":"file_chunk","id":"a","chunk":"BASE64","index":3,"total":9}""")
        assertTrue(chunk is ServerMsg.FileChunk)
        (chunk as ServerMsg.FileChunk).let {
            assertEquals("a", it.id); assertEquals(3, it.index); assertEquals(9, it.total)
        }
        assertTrue(ServerMsg.parse("""{"type":"file_transfer_complete","id":"a"}""") is ServerMsg.FileTransferComplete)
        assertTrue(ServerMsg.parse("""{"type":"file_verify_failed","id":"a"}""") is ServerMsg.FileVerifyFailed)

        val us = ServerMsg.parse("""{"type":"upload_state","id":"a","received":7}""")
        assertEquals(7, (us as ServerMsg.UploadState).received)
    }

    @Test fun parsesErrorAndUnknown() {
        assertEquals("boom", (ServerMsg.parse("""{"type":"error","message":"boom"}""") as ServerMsg.Error).message)
        assertTrue(ServerMsg.parse("""{"type":"made_up"}""") is ServerMsg.Unknown)
        assertTrue(ServerMsg.parse("""not json""") is ServerMsg.Unknown)
    }

    @Test fun clientPushItemShape() {
        val o = JSONObject(ClientMsg.pushItem(sampleItem()))
        assertEquals("push_item", o.getString("type"))
        assertEquals("id-1", o.getJSONObject("item").getString("id"))
        assertTrue(o.getJSONObject("item").has("file_hash"))
    }

    @Test fun clientQueryUploadStateCarriesTotal() {
        val o = JSONObject(ClientMsg.queryUploadState("a", 42))
        assertEquals("query_upload_state", o.getString("type"))
        assertEquals(42, o.getInt("total"))
    }

    @Test fun clientRequestFileOmitsZeroFromChunk() {
        assertFalse(JSONObject(ClientMsg.requestFile("a", 0)).has("from_chunk"))
        assertEquals(5, JSONObject(ClientMsg.requestFile("a", 5)).getInt("from_chunk"))
    }

    @Test fun clientAuthOmitsEmptyKnownIds() {
        assertFalse(JSONObject(ClientMsg.auth("resp", emptyList())).has("known_ids"))
        assertEquals(2, JSONObject(ClientMsg.auth("resp", listOf("a", "b"))).getJSONArray("known_ids").length())
    }
}
