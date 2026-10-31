package dev.loong.runtime

/** 平台库的console最小件（design §5.3 平台库：控制台 I/O）。M1 由代码生成器直接映射。 */
object Console {
    @JvmStatic
    fun println(s: String) {
        System.out.println(s)
    }

    @JvmStatic
    fun print(s: String) {
        System.out.print(s)
    }

    @JvmStatic
    fun concat(a: String, b: String): String = a + b

    /** Int（64 位）的显式字符串转换。 */
    @JvmStatic
    fun str(v: Long): String = v.toString()
}
