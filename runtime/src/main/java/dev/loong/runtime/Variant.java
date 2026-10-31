package dev.loong.runtime;

import java.util.Objects;

/**
 * 枚举值的统一运行时表示（M1）。
 *
 * Loong 的枚举（含内建 Option/Result 与用户枚举）统一编译为 Variant：
 * tag 区分变体，payload 为装箱后的载荷（无载荷为 null）。
 * 相等性按 tag + payload 值比较，支撑语言层的枚举 ==。
 *
 * 纯 Java 实现：字节码契约（构造器 / getTag / getPayload）由代码生成器直接引用，不得改动。
 */
public final class Variant {

    private final String tag;
    private final Object payload;

    public Variant(String tag, Object payload) {
        this.tag = tag;
        this.payload = payload;
    }

    public String getTag() {
        return tag;
    }

    public Object getPayload() {
        return payload;
    }

    public static Variant of(String tag, Object payload) {
        return new Variant(tag, payload);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Variant)) {
            return false;
        }
        Variant v = (Variant) other;
        return v.tag.equals(tag) && Objects.equals(v.payload, payload);
    }

    @Override
    public int hashCode() {
        return tag.hashCode() * 31 + (payload == null ? 0 : payload.hashCode());
    }

    @Override
    public String toString() {
        return payload == null ? tag : tag + "(" + payload + ")";
    }
}
