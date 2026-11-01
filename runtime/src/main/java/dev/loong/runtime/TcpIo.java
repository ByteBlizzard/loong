package dev.loong.runtime;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 平台库的 TCP 最小件（design §5.3 平台库：网络）。阻塞式语义——在 loong
 * 虚拟线程（spawn）上使用；本类只做连接/监听的建立与异常收口，读写见
 * {@link TcpConn} / {@link TcpServer}。UTF-8 文本语义（v1 限制）。
 *
 * 连接/绑定超时 10s；listen 固定绑定回环地址（127.0.0.1）——daemon 侧的
 * agent 服务不应对外网暴露监听面；确需对外监听时用 JVM 生态自行实现。
 *
 * 静态方法签名是 external 声明的调用契约（stdlib/loong/net.loong），不得改动。
 */
public final class TcpIo {

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private TcpIo() {}

    /** 连接 host:port；Ok 载荷为 {@link TcpConn} 句柄，失败 → Err(消息)。 */
    public static Variant connect(String host, long port) {
        try {
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, (int) port), CONNECT_TIMEOUT_MS);
            return Variant.of("Ok", new TcpConn(sock));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 在回环地址监听 port（backlog 50）；Ok 载荷为 {@link TcpServer} 句柄。 */
    public static Variant listen(long port) {
        try {
            ServerSocket ss = new ServerSocket((int) port, 50, java.net.InetAddress.getLoopbackAddress());
            return Variant.of("Ok", new TcpServer(ss));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    private static String err(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
