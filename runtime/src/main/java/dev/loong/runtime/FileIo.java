package dev.loong.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 平台库的文件 I/O 最小件（design §5.3 平台库：控制台与文件 I/O）。
 *
 * loong 无异常：本类把 JDK 文件 API 的受检异常收口为 Result 形态的
 * {@link Variant}（Ok=载荷 / Err=消息字符串），loong 侧声明返回
 * {@code Result<T, String>} 即可 match 处理。编码固定 UTF-8。
 *
 * 纯 Java 实现（零第三方依赖）；静态方法签名是 external 声明的调用契约，
 * stdlib/loong/io.loong 依赖它们，不得改动。
 */
public final class FileIo {

    private FileIo() {}

    /** 读整个文本文件；失败（不存在/无权限/IO）→ Err(消息)。 */
    public static Variant readText(String path) {
        try {
            return Variant.of("Ok", Files.readString(Path.of(path), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 写整个文本文件（CREATE + TRUNCATE_EXISTING）；Ok 载荷为空。 */
    public static Variant writeText(String path, String text) {
        try {
            Files.writeString(Path.of(path), text, StandardCharsets.UTF_8);
            return Variant.of("Ok", null);
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 追加文本（CREATE + APPEND，文件不存在则创建）；Ok 载荷为空。 */
    public static Variant appendText(String path, String text) {
        try {
            Files.writeString(Path.of(path), text, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return Variant.of("Ok", null);
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 路径是否存在（Ok(true/false)，本查询自身失败才 Err）。 */
    public static Variant exists(String path) {
        try {
            return Variant.of("Ok", Files.exists(Path.of(path)));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 是否目录。 */
    public static Variant isDir(String path) {
        try {
            return Variant.of("Ok", Files.isDirectory(Path.of(path)));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 文件字节数（Ok(Long)）。 */
    public static Variant size(String path) {
        try {
            return Variant.of("Ok", Files.size(Path.of(path)));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 列目录下全部条目名（Ok(Object[]) → loong Array<String>，按名排序；非目录/IO → Err）。 */
    public static Variant listDir(String path) {
        try (Stream<Path> s = Files.list(Path.of(path))) {
            return Variant.of("Ok", s.map(p -> p.getFileName().toString()).sorted().toArray(String[]::new));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 递归创建目录（已存在即成功）；Ok 载荷为空。 */
    public static Variant mkdirs(String path) {
        try {
            Files.createDirectories(Path.of(path));
            return Variant.of("Ok", null);
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 删除文件或空目录；Ok(是否真的删除了)，不存在 → Ok(false)。 */
    public static Variant delete(String path) {
        try {
            return Variant.of("Ok", Files.deleteIfExists(Path.of(path)));
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 移动/重命名（同盘原子，跨盘尽力）；Ok 载荷为空。 */
    public static Variant move(String from, String to) {
        try {
            Files.move(Path.of(from), Path.of(to));
            return Variant.of("Ok", null);
        } catch (Exception e) {
            return Variant.of("Err", err(e));
        }
    }

    /** 平台正确的路径拼接（a/b，分隔符由宿主 OS 决定）。 */
    public static String join(String a, String b) {
        return Path.of(a).resolve(b).toString();
    }

    /** 系统临时目录（测试与临时产物的落点；本查询不会失败，直接返回）。 */
    public static String tmpDir() {
        return System.getProperty("java.io.tmpdir");
    }

    private static String err(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
