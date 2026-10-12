package com.sudfry.todo.sync;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.sudfry.todo.store.TodoStore;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同一个 Wi-Fi 下的两台设备互传数据。
 *
 * 协议很简单（行协议 + 长度前缀）：
 *   UDP 47821  探测：`SUDFRY_TODO?`   应答：`SUDFRY_TODO! <设备名> <端口>`
 *   TCP 连接后：
 *     客户端 → `HELLO <配对码> <设备名>`
 *     服务端 → `OK <字节数>` + 对方数据（JSON）
 *     客户端 → `PUSH <字节数>` + 自己的数据
 *     服务端 → `GOT`
 *  也就是双向同步：一次连接，两台设备互相拿到对方的数据，按 uid 合并。
 */
public class SyncEngine {

    public static final int TCP_PORT = 47820;
    public static final int UDP_PORT = 47821;
    private static final String PROBE = "SUDFRY_TODO?";
    private static final String REPLY = "SUDFRY_TODO!";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface Listener {
        void onStatus(String msg);

        void onServerReady(String ip, int port, String code);

        void onDevices(ArrayList<Device> devices);

        void onDone(String summary, boolean isServer);

        void onError(String msg);
    }

    public static class Device {
        public String name = "";
        public String ip = "";
        public int port;

        public String key() {
            return ip + ":" + port;
        }

        @Override
        public String toString() {
            return name + "（" + ip + "）";
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean serving;
    private ServerSocket serverSocket;
    private DatagramSocket responder;
    private Thread serverThread;
    private Thread responderThread;

    public SyncEngine(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    public static String deviceName() {
        String m = Build.MODEL == null ? "Android" : Build.MODEL.trim();
        return m.isEmpty() ? "Android 设备" : m;
    }

    /** 取本机 IPv4 地址（优先 192.168 / 10. / 172.16-31 这类内网地址）。 */
    public static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            for (NetworkInterface ni : Collections.list(nis)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                            || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                            || ip.startsWith("172.2") || ip.startsWith("172.3")) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private void status(String m) {
        main.post(() -> listener.onStatus(m));
    }

    private void error(String m) {
        main.post(() -> listener.onError(m));
    }

    // ───────────────────── 服务端：等对方来连 ─────────────────────

    public void startServer(final String code) {
        stopServer();
        serving = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(TCP_PORT);
                serverSocket.setReuseAddress(true);
            } catch (Exception e) {
                serving = false;
                error("端口被占用，换个网络或稍后再试：" + e.getMessage());
                return;
            }
            main.post(() -> listener.onServerReady(localIp(), TCP_PORT, code));
            status("已就绪，等待对方连接…（配对码 " + code + "）");
            while (serving) {
                try {
                    Socket s = serverSocket.accept();
                    handleClient(s, code);
                } catch (Exception e) {
                    if (serving) {
                        status("连接中断：" + e.getMessage());
                    }
                }
            }
        }, "todo-sync-server");
        serverThread.start();

        responderThread = new Thread(() -> {
            try {
                responder = new DatagramSocket(UDP_PORT);
                responder.setBroadcast(true);
                responder.setReuseAddress(true);
                byte[] buf = new byte[256];
                while (serving) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    responder.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), UTF8).trim();
                    if (msg.startsWith(PROBE)) {
                        String reply = REPLY + " " + deviceName().replace(' ', '_') + " " + TCP_PORT;
                        byte[] out = reply.getBytes(UTF8);
                        responder.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (Exception ignored) {
            }
        }, "todo-sync-udp");
        responderThread.start();
    }

    public void stopServer() {
        serving = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        if (responder != null) {
            responder.close();
            responder = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        if (responderThread != null) {
            responderThread.interrupt();
            responderThread = null;
        }
    }

    private void handleClient(Socket s, String expectedCode) {
        try {
            s.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());

            String hello = readLine(in);
            if (hello == null || !hello.startsWith("HELLO")) {
                writeLine(out, "ERR 缺少握手");
                return;
            }
            String[] parts = hello.split(" ", 3);
            String code = parts.length > 1 ? parts[1] : "";
            String who = parts.length > 2 ? parts[2].replace('_', ' ') : "对方设备";
            if (!expectedCode.isEmpty() && !expectedCode.equals(code)) {
                writeLine(out, "ERR 配对码不对");
                status("有设备尝试连接，但配对码不对，已拒绝");
                return;
            }

            TodoStore store = TodoStore.get(ctx);
            byte[] payload = com.sudfry.todo.store.Backup.export(ctx, deviceName()).getBytes(UTF8);
            writeLine(out, "OK " + payload.length);
            out.write(payload);
            out.flush();
            writeLine(out, "");

            String pushHeader = readLine(in);
            MergeOutcome outcome = new MergeOutcome();
            if (pushHeader != null && pushHeader.startsWith("PUSH")) {
                int len = 0;
                try {
                    len = Integer.parseInt(pushHeader.substring(4).trim());
                } catch (Exception ignored) {
                }
                byte[] data = new byte[Math.max(0, len)];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                String json = new String(data, 0, read, UTF8);
                outcome = merge(json);
            }
            writeLine(out, "GOT");
            out.flush();

            final String summary = "已把本机 " + store.count() + " 条任务、"
                    + com.sudfry.todo.store.EntryStore.get(ctx).count() + " 笔账目发给「" + who + "」，"
                    + outcome.text;
            main.post(() -> listener.onDone(summary, true));
        } catch (Exception e) {
            status("同步出错：" + e.getMessage());
        } finally {
            closeQuietly(s);
        }
    }

    // ───────────────────── 客户端：搜索并拉取 ─────────────────────

    /** UDP 广播搜索同一局域网里正在等待的设备。 */
    public void discover() {
        new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                sock.setSoTimeout(600);
                byte[] probe = PROBE.getBytes(UTF8);
                ArrayList<String> targets = new ArrayList<>();
                targets.add("255.255.255.255");
                String ip = localIp();
                int dot = ip.lastIndexOf('.');
                if (dot > 0) {
                    targets.add(ip.substring(0, dot) + ".255");
                }
                for (String t : targets) {
                    try {
                        sock.send(new DatagramPacket(probe, probe.length,
                                InetAddress.getByName(t), UDP_PORT));
                    } catch (Exception ignored) {
                    }
                }
                Map<String, Device> found = new LinkedHashMap<>();
                long deadline = System.currentTimeMillis() + 2600;
                byte[] buf = new byte[256];
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), UTF8).trim();
                        if (!msg.startsWith(REPLY)) {
                            continue;
                        }
                        String[] parts = msg.split(" ");
                        Device d = new Device();
                        d.name = parts.length > 1 ? parts[1].replace('_', ' ') : "未命名设备";
                        d.ip = p.getAddress().getHostAddress();
                        d.port = parts.length > 2 ? Integer.parseInt(parts[2]) : TCP_PORT;
                        found.put(d.key(), d);
                    } catch (Exception ignored) {
                    }
                }
                ArrayList<Device> list = new ArrayList<>(found.values());
                main.post(() -> listener.onDevices(list));
            } catch (Exception e) {
                error("搜索失败：" + e.getMessage() + "（可以直接手输对方地址）");
            } finally {
                if (sock != null) {
                    sock.close();
                }
            }
        }, "todo-sync-discover").start();
    }

    /** 连接对方设备并双向同步。 */
    public void syncWith(final String ip, final int port, final String code) {
        status("正在连接 " + ip + ":" + port + " …");
        new Thread(() -> {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new java.net.InetSocketAddress(ip, port), 6000);
                s.setSoTimeout(30000);

                BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());

                writeLine(out, "HELLO " + code + " " + deviceName().replace(' ', '_'));
                String header = readLine(in);
                if (header == null) {
                    throw new IOException("对方没有回应");
                }
                if (header.startsWith("ERR")) {
                    throw new IOException(header.length() > 4 ? header.substring(4).trim() : "对方拒绝了连接");
                }
                if (!header.startsWith("OK")) {
                    throw new IOException("协议不匹配：" + header);
                }
                int len = Integer.parseInt(header.substring(2).trim());
                byte[] data = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(data, read, len - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                readLine(in);   // 吃掉结尾换行

                TodoStore store = TodoStore.get(ctx);
                MergeOutcome a = merge(new String(data, 0, read, UTF8));

                byte[] mine = com.sudfry.todo.store.Backup.export(ctx, deviceName()).getBytes(UTF8);
                writeLine(out, "PUSH " + mine.length);
                out.write(mine);
                out.flush();
                writeLine(out, "");
                String ack = readLine(in);

                final String summary = "收到对方 " + a.total + " 项："
                        + a.text + ("GOT".equals(ack) ? "；本机数据已回传" : "");
                main.post(() -> listener.onDone(summary, false));
            } catch (Exception e) {
                error("同步失败：" + e.getMessage());
            } finally {
                closeQuietly(s);
            }
        }, "todo-sync-client").start();
    }

    // ───────────────────── 小工具 ─────────────────────

    private static class MergeOutcome {
        int total;
        String text = "";

        String text() {
            return text;
        }
    }

    private MergeOutcome merge(String json) {
        MergeOutcome o = new MergeOutcome();
        try {
            JSONObject root = new JSONObject(json);
            org.json.JSONArray arr = root.optJSONArray("items");
            o.total = arr == null ? 0 : arr.length();
            com.sudfry.todo.store.Backup.Result r =
                    com.sudfry.todo.store.Backup.importJson(ctx, json, false);
            o.text = r.detail().replace("\n", "；");
        } catch (Exception e) {
            o.total = 0;
            o.text = "解析失败：" + e.getMessage();
        }
        return o;
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(UTF8));
        out.flush();
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean eof = false;
        while (true) {
            int c = in.read();
            if (c == -1) {
                eof = true;
                break;
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (eof && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(ServerSocket c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(Socket c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
        }
    }
}
