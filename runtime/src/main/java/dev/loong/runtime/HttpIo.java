package dev.loong.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 平台库的 HTTP 客户端最小件（design §5.3 平台库：网络），基于 JDK 内置
 * HttpClient（零第三方依赖）。阻塞式调用——在 loong 虚拟线程（spawn）上使用。
 *
 * 异常收口同 {@link FileIo}：transport 异常 → Err(消息)；HTTP 非 2xx →
 * Err("HTTP <status>")（响应体不返回，保持 v1 文本级语义）。Ok 一律为响应体字符串。
 * 编码固定 UTF-8；连接超时 10s，请求超时 60s，重定向 NORMAL。
 *
 * 静态方法签名是 external 声明的调用契约（stdlib/loong/net.loong），不得改动。
 */
public final class HttpIo {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private HttpIo() {}

    /** GET url；2xx → Ok(body)，否则 Err。 */
    public static Variant get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            return respond(CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** POST 文本体（指定 Content-Type，如 "application/json"）；2xx → Ok(body)，否则 Err。 */
    public static Variant post(String url, String body, String contentType) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            return respond(CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    private static Variant respond(HttpResponse<String> resp) {
        if (resp.statusCode() / 100 == 2) {
            return Variant.of("Ok", resp.body());
        }
        String body = resp.body() == null ? "" : resp.body();
        String hint = body.length() > 160 ? body.substring(0, 160) + "..." : body;
        return Variant.of("Err", "HTTP " + resp.statusCode() + (hint.isBlank() ? "" : ": " + hint));
    }

    private static String err(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
