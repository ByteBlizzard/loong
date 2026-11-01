package dev.loong.runtime;

import java.net.ServerSocket;

/**
 * TCP 服务端监听句柄（由 {@link TcpIo#listen} 建立，loong 侧不直接构造）。
 *
 * accept 阻塞等待新连接（虚拟线程友好：每个 accept spawn 一个处理任务）；
 * close 尽力而为、不抛。异常收口同 {@link FileIo}。
 *
 * 实例方法签名是 external 声明（instance + self 约定）的调用契约
 * （stdlib/loong/net.loong），不得改动。
 */
public final class TcpServer {

    private final ServerSocket ss;

    TcpServer(ServerSocket ss) {
        this.ss = ss;
    }

    /** 阻塞接受一个连接；Ok 载荷为 {@link TcpConn}，失败（已关闭/IO）→ Err(消息)。 */
    public Variant accept() {
        try {
            return Variant.of("Ok", new TcpConn(ss.accept()));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 实际监听端口（listen(0) 让 OS 分配临时端口后，用它取回端口号）。 */
    public long port() {
        return ss.getLocalPort();
    }

    /** 关闭监听（尽力而为，不抛异常、无返回值）。 */
    public void close() {
        try {
            ss.close();
        } catch (Exception ignored) {
            // close 尽力而为
        }
    }

    private static String err(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
