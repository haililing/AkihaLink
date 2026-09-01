package com.akiha.akihalink.speedtest

import com.akiha.akihalink.data.NodeSort
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLatencyResultTest {
    @Test
    fun persistsStableWireNamesAndTimestamps() {
        val result = NodeLatencyResult(NodeLatencyStatus.UNSTABLE, 214, 123456L)
        val encoded = Json.encodeToString(result)

        assertTrue(encoded.contains("\"status\":\"unstable\""))
        assertEquals(result, Json.decodeFromString<NodeLatencyResult>(encoded))
    }

    @Test
    fun unknownSortValuesFallBackToName() {
        assertEquals(NodeSort.NAME, NodeSort.fromWireName(null))
        assertEquals(NodeSort.NAME, NodeSort.fromWireName("unexpected"))
        assertEquals(NodeSort.LATENCY, NodeSort.fromWireName("latency"))
        assertEquals(NodeSort.NAME, NodeSort.fromWireName("quality"))
    }
}
