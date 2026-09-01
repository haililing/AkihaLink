package com.akiha.akihalink.dataplanebenchmark;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class DataPlaneInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle input) {
        arguments = input == null ? new Bundle() : input;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String selector = arguments.getString("class", "");
            String workload = arguments.getString("workload", "");
            if (workload.isEmpty() && selector.contains("#")) {
                workload = selector.substring(selector.indexOf('#') + 1);
            }
            switch (workload) {
                case "tcpDownload" -> runTcp(true);
                case "tcpUpload" -> runTcp(false);
                case "tcpConnectLatency" -> runTcpConnectLatency();
                case "udp1200BytePpsAndLoss" -> runUdp();
                default -> throw new IllegalArgumentException("Unknown workload: " + workload);
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Log.e("AkihaLinkDataPlane", "benchmark failed", error);
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String host() {
        String value = arguments.getString("serverHost");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("serverHost is required");
        return value;
    }

    private int integer(String name, int fallback) {
        String value = arguments.getString(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private long durationMillis() { return integer("durationSeconds", 30) * 1_000L; }

    private void runTcpConnectLatency() throws Exception {
        long[] samples = new long[50];
        for (int index = 0; index < samples.length; index++) {
            long started = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host(), integer("tcpPort", 5201)), 5_000);
                socket.getOutputStream().write("PING\n".getBytes(StandardCharsets.UTF_8));
                String reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                if (!"PONG".equals(reply)) throw new IllegalStateException("unexpected reply");
            }
            samples[index] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        int p95Index = Math.max(0, (int) Math.ceil(samples.length * 0.95) - 1);
        emit("tcp_connect", "\"samples\":" + samples.length + ",\"p95Millis\":" + samples[p95Index] / 1_000_000.0);
    }

    private void runUdp() throws Exception {
        long duration = durationMillis();
        long deadline = System.nanoTime() + duration * 1_000_000L;
        long drainDeadline = deadline + 1_000_000_000L;
        AtomicLong sent = new AtomicLong();
        AtomicLong received = new AtomicLong();
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.configureBlocking(false);
            InetSocketAddress destination = new InetSocketAddress(
                    InetAddress.getByName(host()), integer("udpPort", 5202));
            byte[] payload = new byte[1_200];
            ByteBuffer packet = ByteBuffer.allocate(1_200);
            int burst = 0;
            while (System.nanoTime() < deadline) {
                long sequence = sent.get();
                for (int index = 0; index < 8; index++) payload[index] = (byte) (sequence >>> (index * 8));
                int count = channel.send(ByteBuffer.wrap(payload), destination);
                if (count == payload.length) {
                    sent.incrementAndGet();
                    burst++;
                } else {
                    LockSupport.parkNanos(100_000L);
                }
                if (burst >= 64) {
                    while (true) {
                        packet.clear();
                        if (channel.receive(packet) == null) break;
                        received.incrementAndGet();
                    }
                    burst = 0;
                }
            }
            while (System.nanoTime() < drainDeadline) {
                packet.clear();
                if (channel.receive(packet) != null) received.incrementAndGet();
                else LockSupport.parkNanos(100_000L);
            }
        }
        long sentCount = sent.get();
        long receivedCount = received.get();
        double pps = receivedCount * 1_000.0 / duration;
        double loss = Math.max(0, sentCount - receivedCount) * 100.0 / Math.max(1, sentCount);
        emit("udp_1200", "\"sent\":" + sentCount + ",\"received\":" + receivedCount + ",\"pps\":" + pps + ",\"lossPercent\":" + loss);
    }

    private void runTcp(boolean download) throws Exception {
        int concurrency = integer("concurrency", 1);
        if (concurrency != 1 && concurrency != 4) throw new IllegalArgumentException("concurrency must be 1 or 4");
        long duration = durationMillis();
        AtomicLong total = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(concurrency);
        AtomicLong failure = new AtomicLong();
        long started = System.nanoTime();
        for (int index = 0; index < concurrency; index++) {
            Thread worker = new Thread(() -> {
                try (Socket socket = new Socket()) {
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(host(), integer("tcpPort", 5201)), 5_000);
                    socket.setSoTimeout(1_000);
                    long deadline = System.nanoTime() + duration * 1_000_000L;
                    byte[] buffer = new byte[128 << 10];
                    if (download) {
                        socket.getOutputStream().write("DOWNLOAD 1099511627776\n".getBytes(StandardCharsets.UTF_8));
                        while (System.nanoTime() < deadline) {
                            int count;
                            try {
                                count = socket.getInputStream().read(buffer);
                            } catch (SocketTimeoutException timeout) {
                                if (System.nanoTime() >= deadline) break;
                                continue;
                            } catch (SocketException closedAtDeadline) {
                                if (System.nanoTime() + 250_000_000L >= deadline && total.get() > 0) break;
                                throw closedAtDeadline;
                            }
                            if (count < 0) break;
                            total.addAndGet(count);
                        }
                    } else {
                        socket.getOutputStream().write("UPLOAD\n".getBytes(StandardCharsets.UTF_8));
                        while (System.nanoTime() < deadline) {
                            try {
                                socket.getOutputStream().write(buffer);
                                total.addAndGet(buffer.length);
                            } catch (SocketException closedAtDeadline) {
                                if (System.nanoTime() + 250_000_000L >= deadline && total.get() > 0) break;
                                throw closedAtDeadline;
                            }
                        }
                    }
                } catch (Exception error) {
                    failure.incrementAndGet();
                    Log.e("AkihaLinkDataPlane", "TCP worker failed", error);
                } finally {
                    latch.countDown();
                }
            }, "akl-tcp-" + index);
            worker.start();
        }
        latch.await();
        if (failure.get() != 0) throw new IllegalStateException("TCP workers failed: " + failure.get());
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        emit(download ? "tcp_download" : "tcp_upload", "\"concurrency\":" + concurrency + ",\"bytes\":" + total.get() + ",\"seconds\":" + seconds + ",\"bitsPerSecond\":" + total.get() * 8.0 / seconds);
    }

    private void emit(String workload, String fields) {
        Log.i("AkihaLinkDataPlane", "{\"workload\":\"" + workload + "\"," + fields + "}");
    }
}
