package com.cue.simplebrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Selective DNS-only prototype. It never routes general app traffic or falls back to clear DNS. */
public final class DnsVpnService extends VpnService {
    public static final String ACTION_START = "com.cue.simplebrowser.dnsvpn.START";
    public static final String ACTION_STOP = "com.cue.simplebrowser.dnsvpn.STOP";
    private static final String TAG = "SimpleBrowserDnsVpn";
    private static final String CHANNEL_ID = "dns_vpn_experimental";
    private static final int NOTIFICATION_ID = 21710;
    private static final int MAX_TCP_SESSIONS = 64;
    private static final int MAX_TCP_RESPONSE_SEGMENTS = 256;
    private static final int MAX_TCP_DNS_RESPONSE_BYTES = 65533;
    private static final long TCP_SESSION_TIMEOUT_MS = 30000L;

    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_CONNECTED_EXPERIMENTAL = 2;
    public static final int STATE_ERROR = 3;
    private static volatile int state = STATE_STOPPED;
    private static volatile boolean tunnelConnected;
    private static volatile String stateDetail = "";
    private static volatile long queriesOk;
    private static volatile long queriesFailed;
    private static volatile long plaintextFallbackAttempts;

    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private FileInputStream tunInput;
    private OutputStream tunOutput;
    private Thread packetThread;
    private ExecutorService dohExecutor;
    private DohClient dohClient;
    private final Object writeLock = new Object();
    private final Map<String, TcpSession> tcpSessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public static int getState() { return state; }
    public static boolean isTunnelActuallyConnected() {
        return tunnelConnected && state == STATE_CONNECTED_EXPERIMENTAL;
    }
    public static String getStateDetail() { return stateDetail; }
    public static long getQueriesSucceeded() { return queriesOk; }
    public static long getQueriesFailed() { return queriesFailed; }
    public static long getPlaintextFallbackAttempts() { return plaintextFallbackAttempts; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running && isTunnelActuallyConnected()) return START_STICKY;
        if (running) {
            running = false;
            closeTun();
        }
        tunnelConnected = false;
        setState(STATE_STARTING, "正在建立 DNS-only TUN");
        try {
            startForegroundCompat();
            dohClient = DohClient.quad9(this::protect);
            tun = establishDnsOnlyTun();
            if (tun == null) throw new IOException("系统未能建立 VPN TUN");
            tunInput = new FileInputStream(tun.getFileDescriptor());
            tunOutput = new java.io.FileOutputStream(tun.getFileDescriptor());
            dohExecutor = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "dns-doh-worker");
                thread.setDaemon(true);
                return thread;
            });
            running = true;
            tunnelConnected = tun != null && tun.getFileDescriptor().valid();
            if (!tunnelConnected) throw new IOException("系统 DNS-only TUN 未保持有效连接");
            setState(STATE_CONNECTED_EXPERIMENTAL,
                    "TUN 已建立；选择性 DNS-only 实验，GeckoView 解析路径未验证");
            packetThread = new Thread(this::readTunPackets, "dns-only-tun-reader");
            packetThread.setDaemon(true);
            packetThread.start();
            return START_STICKY;
        } catch (Exception error) {
            Log.e(TAG, "Unable to start selective DNS VPN", error);
            setState(STATE_ERROR, "VPN 未就绪；未回退到明文 DNS");
            closeTun();
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
    }

    private ParcelFileDescriptor establishDnsOnlyTun() throws Exception {
        Builder builder = new Builder()
                .setSession("简浏览 · DNS-only 实验")
                .setMtu(DnsPacketCodec.MAX_TUN_MTU)
                .addAddress("10.223.0.1", 32)
                .addAddress("fd23:9c3a:1::1", 128)
                .addAllowedApplication(getPackageName());
        for (String resolver : DnsRoutingPolicy.DNS_SERVERS) {
            builder.addDnsServer(resolver);
            builder.addRoute(resolver, DnsRoutingPolicy.prefixLength(resolver));
        }
        return builder.establish();
    }

    private void startForegroundCompat() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "简浏览 DNS 实验模式", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示 DNS-only VPN 实验运行状态");
        manager.createNotificationChannel(channel);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("简浏览 DNS-only 实验")
                .setContentText("选择性隧道运行中；解析路径未验证")
                .setOngoing(true)
                .setContentIntent(pendingIntent);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onRevoke() {
        tunnelConnected = false;
        setState(STATE_STOPPED, "系统撤销 VPN 授权；DNS 实验未激活");
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;
        tunnelConnected = false;
        if (packetThread != null) packetThread.interrupt();
        if (dohExecutor != null) dohExecutor.shutdownNow();
        tcpSessions.clear();
        closeTun();
        stopForegroundCompat();
        if (state != STATE_ERROR && state != STATE_STOPPED) setState(STATE_STOPPED, "DNS 实验未激活");
        super.onDestroy();
    }

    private void readTunPackets() {
        byte[] buffer = new byte[32767];
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int count = tunInput.read(buffer);
                if (count < 0) break;
                DnsPacketCodec.Packet packet = DnsPacketCodec.parse(buffer, count);
                expireTcpSessions();
                if (packet.destinationPort != 53) continue;
                if (packet.protocol == DnsPacketCodec.UDP) handleUdp(packet);
                else if (packet.protocol == DnsPacketCodec.TCP) handleTcp(packet);
            } catch (IOException error) {
                if (running) Log.w(TAG, "Dropped malformed/unsupported TUN packet", error);
            } catch (RuntimeException error) {
                if (running) Log.e(TAG, "Dropped invalid TUN packet", error);
            }
        }
        if (running) {
            setState(STATE_ERROR, "TUN 已断开；未回退到明文 DNS");
            stopSelf();
        }
    }

    private void handleUdp(DnsPacketCodec.Packet packet) {
        final byte[] query = packet.payload;
        try {
            DnsWire.validateQuery(query);
        } catch (IOException malformed) {
            queriesFailed++;
            return;
        }
        dohExecutor.execute(() -> {
            try {
                byte[] response = dohClient.exchange(query);
                writeTun(DnsPacketCodec.buildUdpResponse(packet, response));
                queriesOk++;
            } catch (Exception error) {
                queriesFailed++;
                Log.w(TAG, "DoH failed closed; no DNS fallback was attempted", error);
                setState(STATE_CONNECTED_EXPERIMENTAL, "DoH 失败；查询已丢弃，未回退到明文 DNS");
            }
        });
    }

    private void handleTcp(DnsPacketCodec.Packet packet) {
        String key = packet.flowKey();
        int flags = packet.tcpFlags;
        TcpSession session = tcpSessions.get(key);
        if ((flags & DnsPacketCodec.TCP_RST) != 0) {
            if (session != null) tcpSessions.remove(key);
            return;
        }
        if ((flags & DnsPacketCodec.TCP_SYN) != 0) {
            if (session == null) {
                if (tcpSessions.size() >= MAX_TCP_SESSIONS) {
                    sendTcpReset(packet);
                    return;
                }
                session = new TcpSession(packet, packet.sequence + 1, random.nextInt());
                tcpSessions.put(key, session);
            }
            session.lastActivity = System.currentTimeMillis();
            sendTcp(session, session.synPacket, session.serverInitialSequence,
                    session.clientNextSequence, DnsPacketCodec.TCP_SYN | DnsPacketCodec.TCP_ACK, null);
            return;
        }
        if (session == null) {
            sendTcpReset(packet);
            return;
        }
        session.lastActivity = System.currentTimeMillis();
        session.lastPacket = packet;
        if (packet.payload.length > 0) {
            if (packet.sequence != session.clientNextSequence) {
                if (packet.sequence < session.clientNextSequence && session.responseSegments != null) {
                    resendTcpResponse(session);
                } else {
                    sendTcpAck(session);
                }
                return;
            }
            if (session.querySubmitted || session.request.size() + packet.payload.length > 65537) {
                sendTcpReset(packet);
                tcpSessions.remove(key);
                return;
            }
            session.request.write(packet.payload, 0, packet.payload.length);
            session.clientNextSequence += packet.payload.length;
            sendTcpAck(session);
            byte[] framed = session.request.toByteArray();
            if (framed.length >= 2) {
                int dnsLength = ((framed[0] & 0xff) << 8) | (framed[1] & 0xff);
                if (dnsLength < 12 || dnsLength > DohClient.MAX_DNS_BODY_BYTES) {
                    sendTcpReset(packet);
                    tcpSessions.remove(key);
                    return;
                }
                if (framed.length >= dnsLength + 2) {
                    if (framed.length != dnsLength + 2) {
                        sendTcpReset(packet);
                        tcpSessions.remove(key);
                        return;
                    }
                    byte[] query = Arrays.copyOfRange(framed, 2, framed.length);
                    try { DnsWire.validateQuery(query); }
                    catch (IOException malformed) {
                        queriesFailed++;
                        sendTcpReset(packet);
                        tcpSessions.remove(key);
                        return;
                    }
                    session.querySubmitted = true;
                    final TcpSession captured = session;
                    dohExecutor.execute(() -> answerTcp(captured, query));
                }
            }
        }
        if ((flags & DnsPacketCodec.TCP_FIN) != 0) {
            session.clientNextSequence++;
            session.clientFinReceived = true;
            sendTcpAck(session);
            if (session.responseSegments != null && !session.serverFinSent) {
                sendTcp(session, packet, session.serverNextSequence, session.clientNextSequence,
                        DnsPacketCodec.TCP_FIN | DnsPacketCodec.TCP_ACK, null);
                session.serverNextSequence++;
                session.serverFinSent = true;
                tcpSessions.remove(key, session);
            }
        }
    }

    private void answerTcp(TcpSession session, byte[] query) {
        try {
            byte[] response = dohClient.exchange(query);
            if (response.length > MAX_TCP_DNS_RESPONSE_BYTES) {
                throw new IOException("DNS-over-TCP response exceeds the bounded receive window");
            }
            ByteArrayOutputStream framed = new ByteArrayOutputStream(response.length + 2);
            framed.write((response.length >>> 8) & 0xff);
            framed.write(response.length & 0xff);
            framed.write(response);
            byte[] bytes = framed.toByteArray();
            synchronized (session) {
                if (tcpSessions.get(session.key) != session) return;
                session.responseBytes = bytes;
                byte[][] packets = buildResponsePackets(session, bytes);
                for (byte[] packet : packets) writeTun(packet);
                session.serverNextSequence += bytes.length;
                session.responseSegments = packets;
                session.responseSent = true;
                queriesOk++;
                if (session.clientFinReceived && !session.serverFinSent) {
                    sendTcp(session, session.lastPacket, session.serverNextSequence,
                            session.clientNextSequence, DnsPacketCodec.TCP_FIN | DnsPacketCodec.TCP_ACK, null);
                    session.serverNextSequence++;
                    session.serverFinSent = true;
                    tcpSessions.remove(session.key, session);
                }
            }
        } catch (Exception error) {
            queriesFailed++;
            Log.w(TAG, "TCP DNS DoH failed closed; no DNS fallback was attempted", error);
            sendTcpReset(session.lastPacket);
            tcpSessions.remove(session.key, session);
            setState(STATE_CONNECTED_EXPERIMENTAL, "TCP DNS DoH 失败；查询已丢弃，未回退到明文 DNS");
        }
    }

    private byte[][] buildResponsePackets(TcpSession session, byte[] response) throws IOException {
        int start = session.serverNextSequence;
        int segmentSize = Math.min(DnsPacketCodec.TCP_SEGMENT_SIZE, session.peerMss);
        int count = (response.length + segmentSize - 1) / segmentSize;
        if (count > MAX_TCP_RESPONSE_SEGMENTS) throw new IOException("Too many TCP DNS response segments");
        byte[][] packets = new byte[count][];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            int length = Math.min(segmentSize, response.length - offset);
            byte[] payload = Arrays.copyOfRange(response, offset, offset + length);
            int segmentFlags = DnsPacketCodec.TCP_ACK | (i == count - 1 ? DnsPacketCodec.TCP_PSH : 0);
            packets[i] = DnsPacketCodec.buildTcpResponse(session.lastPacket, start + offset,
                    session.clientNextSequence, segmentFlags, payload);
            offset += length;
        }
        return packets;
    }

    private void resendTcpResponse(TcpSession session) {
        if (session.responseSegments == null) return;
        for (byte[] packet : session.responseSegments) writeTun(packet);
    }

    private void sendTcpAck(TcpSession session) {
        sendTcp(session, session.lastPacket, session.serverNextSequence,
                session.clientNextSequence, DnsPacketCodec.TCP_ACK, null);
    }

    private void sendTcp(TcpSession session, DnsPacketCodec.Packet packet, int sequence,
                         int acknowledgement, int flags, byte[] payload) {
        try {
            writeTun(DnsPacketCodec.buildTcpResponse(packet, sequence, acknowledgement, flags, payload));
        } catch (IOException error) { Log.w(TAG, "Could not write TCP DNS packet", error); }
    }

    private void sendTcpReset(DnsPacketCodec.Packet packet) {
        if (packet == null) return;
        int acknowledgement = packet.sequence + packet.payload.length
                + ((packet.tcpFlags & DnsPacketCodec.TCP_SYN) != 0 ? 1 : 0)
                + ((packet.tcpFlags & DnsPacketCodec.TCP_FIN) != 0 ? 1 : 0);
        int flags = (packet.tcpFlags & DnsPacketCodec.TCP_ACK) != 0
                ? DnsPacketCodec.TCP_RST
                : DnsPacketCodec.TCP_RST | DnsPacketCodec.TCP_ACK;
        int sequence = (packet.tcpFlags & DnsPacketCodec.TCP_ACK) != 0 ? packet.acknowledgement : 0;
        try { writeTun(DnsPacketCodec.buildTcpResponse(packet, sequence, acknowledgement, flags, null)); }
        catch (IOException error) { Log.w(TAG, "Could not write TCP DNS RST", error); }
    }

    private void writeTun(byte[] packet) {
        try {
            synchronized (writeLock) {
                if (running && tunOutput != null) {
                    tunOutput.write(packet);
                    tunOutput.flush();
                }
            }
        } catch (IOException error) {
            if (running) Log.w(TAG, "Unable to write DNS response to TUN", error);
        }
    }

    private void expireTcpSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TcpSession> entry : tcpSessions.entrySet()) {
            if (now - entry.getValue().lastActivity > TCP_SESSION_TIMEOUT_MS) {
                tcpSessions.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void closeTun() {
        tunnelConnected = false;
        running = false;
        try { if (tunInput != null) tunInput.close(); } catch (IOException ignored) { }
        try { if (tunOutput != null) tunOutput.close(); } catch (IOException ignored) { }
        try { if (tun != null) tun.close(); } catch (IOException ignored) { }
        tunInput = null;
        tunOutput = null;
        tun = null;
    }

    private void stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private static void setState(int newState, String detail) {
        state = newState;
        stateDetail = detail == null ? "" : detail;
    }

    private final class TcpSession {
        final String key;
        final DnsPacketCodec.Packet synPacket;
        volatile DnsPacketCodec.Packet lastPacket;
        final int serverInitialSequence;
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        volatile int clientNextSequence;
        volatile int serverNextSequence;
        volatile long lastActivity = System.currentTimeMillis();
        volatile boolean querySubmitted;
        volatile boolean responseSent;
        volatile boolean clientFinReceived;
        volatile boolean serverFinSent;
        volatile byte[] responseBytes;
        volatile byte[][] responseSegments;
        final int peerMss;
        TcpSession(DnsPacketCodec.Packet packet, int clientNext, int serverInitial) {
            key = packet.flowKey();
            synPacket = packet;
            lastPacket = packet;
            clientNextSequence = clientNext;
            serverInitialSequence = serverInitial;
            serverNextSequence = serverInitial + 1;
            peerMss = packet.tcpMss > 0 ? packet.tcpMss : (packet.ipVersion == DnsPacketCodec.IPV6 ? 1220 : 536);
        }
    }
}
