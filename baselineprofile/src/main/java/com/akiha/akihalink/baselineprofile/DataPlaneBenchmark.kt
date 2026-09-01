package com.akiha.akihalink.baselineprofile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.ceil
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataPlaneBenchmark {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val host get() = arguments.getString("serverHost") ?: error("serverHost is required")
    private val tcpPort get() = arguments.getString("tcpPort")?.toInt() ?: 5201
    private val udpPort get() = arguments.getString("udpPort")?.toInt() ?: 5202
    private val durationMillis get() = arguments.getString("durationSeconds")?.toLong()?.times(1_000) ?: 30_000
    private val concurrency get() = arguments.getString("concurrency")?.toInt() ?: 1

    @Test fun tcpDownload() = runTCP(download = true)
    @Test fun tcpUpload() = runTCP(download = false)

    @Test fun tcpConnectLatency() {
        val samples = LongArray(50)
        repeat(samples.size) { index ->
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, tcpPort), 5_000)
                socket.getOutputStream().write("PING\n".toByteArray())
                val reply = socket.getInputStream().bufferedReader().readLine()
                check(reply == "PONG")
            }
            samples[index] = System.nanoTime() - started
        }
        samples.sort()
        val p95 = samples[(ceil(samples.size * 0.95).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
        emit("tcp_connect", mapOf("samples" to samples.size, "p95Millis" to p95))
    }

    @Test fun udp1200BytePpsAndLoss() {
        val address = InetAddress.getByName(host)
        val sent = AtomicLong()
        val received = AtomicLong()
        val deadline = System.nanoTime() + durationMillis * 1_000_000
        DatagramSocket().use { socket ->
            socket.connect(address, udpPort)
            socket.soTimeout = 100
            val payload = ByteArray(1_200)
            val receiver = thread(name = "akl-udp-receiver") {
                val packet = DatagramPacket(ByteArray(1_200), 1_200)
                while (System.nanoTime() < deadline || received.get() < sent.get()) {
                    try {
                        socket.receive(packet)
                        received.incrementAndGet()
                    } catch (_: java.net.SocketTimeoutException) {
                        if (System.nanoTime() >= deadline) break
                    }
                }
            }
            while (System.nanoTime() < deadline) {
                val sequence = sent.getAndIncrement()
                for (index in 0 until 8) payload[index] = (sequence ushr (index * 8)).toByte()
                socket.send(DatagramPacket(payload, payload.size))
            }
            receiver.join(2_000)
        }
        val sentCount = sent.get()
        val receivedCount = received.get()
        emit(
            "udp_1200",
            mapOf(
                "sent" to sentCount,
                "received" to receivedCount,
                "pps" to (receivedCount * 1_000.0 / durationMillis),
                "lossPercent" to (max(0, sentCount - receivedCount) * 100.0 / max(1, sentCount)),
            ),
        )
    }

    private fun runTCP(download: Boolean) {
        require(concurrency in setOf(1, 4)) { "concurrency must be 1 or 4" }
        val total = AtomicLong()
        val latch = CountDownLatch(concurrency)
        val started = System.nanoTime()
        repeat(concurrency) {
            thread(name = "akl-tcp-$it") {
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(host, tcpPort), 5_000)
                        val deadline = System.nanoTime() + durationMillis * 1_000_000
                        if (download) {
                            socket.getOutputStream().write("DOWNLOAD ${1L shl 40}\n".toByteArray())
                            val buffer = ByteArray(128 shl 10)
                            while (System.nanoTime() < deadline) {
                                val count = socket.getInputStream().read(buffer)
                                if (count < 0) break
                                total.addAndGet(count.toLong())
                            }
                        } else {
                            socket.getOutputStream().write("UPLOAD\n".toByteArray())
                            val buffer = ByteArray(128 shl 10)
                            while (System.nanoTime() < deadline) {
                                socket.getOutputStream().write(buffer)
                                total.addAndGet(buffer.size.toLong())
                            }
                            socket.shutdownOutput()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        emit(
            if (download) "tcp_download" else "tcp_upload",
            mapOf(
                "concurrency" to concurrency,
                "bytes" to total.get(),
                "seconds" to elapsedSeconds,
                "bitsPerSecond" to total.get() * 8.0 / elapsedSeconds,
            ),
        )
    }

    private fun emit(name: String, values: Map<String, Any>) {
        val fields = values.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
        Log.i("AkihaLinkDataPlane", "{\"workload\":\"$name\",$fields}")
    }
}
