package dev.loong.runtime;

/**
 * loong-runtime：编译器隐式链接的运行时 shim。
 *
 * M1 起承载 Option/Result/Mutex/Channel/Handle 的 JVM 实现；
 * 后续逐步用 Loong 自身重写（自举，plan.md M7+）。
 */
public final class LoongRuntime {

    public static final String VERSION = "0.1.0";

    private LoongRuntime() {}
}
