package dev.loong.runtime

/**
 * 函数值的擦除运行时接口（v1）。
 *
 * Loong 无闭包语法：高阶函数的实参一律是具名函数值。编译器为每个被引用为值的
 * 函数生成伴生适配类实现对应元数的 Fn 接口——apply 内 invokestatic 直呼本体，
 * 参数与返回装箱（不使用 invokedynamic，design §6.2 v1 边界）。
 * 捕获语义由显式参数穿引承担（待决 #3 的答案，2026-10-30）。
 */
interface Fn0 {
    fun apply(): Any?
}

interface Fn1 {
    fun apply(a: Any?): Any?
}

interface Fn2 {
    fun apply(a: Any?, b: Any?): Any?
}

interface Fn3 {
    fun apply(a: Any?, b: Any?, c: Any?): Any?
}
