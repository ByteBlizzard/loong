package dev.loong.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TCP 连接句柄（由 {@link TcpIo#connect} 建立，loong 侧不直接构造）。
 *
 * 阻塞式读写，UTF-8 文本语义（v1 限制）：send 发整段文本并 flush；
 * recv 阻塞读到至多 maxLen 字节，对端关闭返回 Ok("")（空串即 EOF 信号）。
 * 异常收口同 {@link FileIo}；close 尽力而为、不抛。
 *
 * 实例方法签名是 external 声明（instance + self 约定）的调用契约
 * （stdlib/loong/net.loong），不得改动。
 */
public final class TcpConn {

    private final Socket sock;
    private final OutputStream out;
    private final InputStream in;

    TcpConn(Socket sock) throws java.io.IOException {
        this.sock = sock;
        this.out = sock.getOutputStream();
        this.in = sock.getInputStream();
    }

    /** 发送文本；Ok(发送字节数)，失败（已关闭/IO）→ Err(消息)。 */
    public Variant send(String data) {
        try {
            byte[] bs = data.getBytes(StandardCharsets.UTF_8);
            out.write(bs);
            out.flush();
            return Variant.of("Ok", (long) bs.length);
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 阻塞接收至多 maxLen 字节的文本；Ok("") 表示对端已关闭（EOF）。 */
    public Variant recv(long maxLen) {
        try {
            int n = (int) Math.max(0, maxLen);
            byte[] buf = new byte[n];
            int read = in.read(buf, 0, n);
            if (read < 0) {
                return Variant.of("Ok", "");
            }
            return Variant.of("Ok", new String(buf, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 关闭连接（尽力而为，不抛异常、无返回值）。 */
    public void close() {
        try {
            sock.close();
        } catch (Exception ignored) {
            // close 尽力而为：对端已死/已关都视为达成目的
        }
    }

    private static String err(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
