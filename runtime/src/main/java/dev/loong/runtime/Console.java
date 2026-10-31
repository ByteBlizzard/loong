package dev.loong.runtime;

/**
 * 平台库的 console 最小件（design §5.3 平台库：控制台 I/O）。M1 由代码生成器直接映射。
 *
 * 纯 Java 实现：loong 产物（构建 jar）不携带任何宿主工具链运行时（ADR：runtime 零依赖），
 * 只依赖 JDK。签名是代码生成器的调用契约（INVOKESTATIC），不得改动。
 */
public final class Console {

    private Console() {}

    public static void println(String s) {
        System.out.println(s);
    }

    public static void print(String s) {
        System.out.print(s);
    }

    public static String concat(String a, String b) {
        return a + b;
    }

    /** Int（64 位）的显式字符串转换。 */
    public static String str(long v) {
        return Long.toString(v);
    }
}
