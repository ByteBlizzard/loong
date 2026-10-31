package dev.loong.daemon.server

import dev.loong.engine.workspace.Workspace

/**
 * 空项目的缺省模块（「建个目录、cd 进去、敲 loong」后的初始内容）。
 *
 * - main：最小可运行入口，向控制台问候；
 * - example：演示语言能力的 AST 样板——变量（let/var）、类型（Int/Float/Bool/Char/
 *   String/Array/struct/enum）、函数定义与调用（具名实参、泛型 where）、接口与实现
 *   （接收者型参约定 + Iterable 显式状态机迭代）、控制语句（if/while/for/match/break）。
 *
 * 源码经 [Workspace.load] 解析为 AST 节点表，与普通模块同路径持久化；内容满足
 * 强制规则（doc 注释 / public 函数配测试），装载后即为零诊断。
 */
internal object DefaultModules {

    /** 缺省入口模块。 */
    val MAIN = """
        module main

        /// Entry point of a fresh loong project: greet the console.
        function main() -> Unit {
            println("Hello , loong!")
        }
    """.trimIndent()

    /** 能力演示模块。 */
    val EXAMPLE = """
        module example

        /// A point in the plane.
        struct Point {
            /// x coordinate
            x: Int
            /// y coordinate
            y: Int
        }

        /// Shapes with a computable area.
        interface Shape<S> {
            /// Area of the shape.
            function area(self: S) -> Int
        }

        impl Shape<Point> for Point {
            /// Rectangle area: width times height.
            function area(self: Point) -> Int {
                return self.x * self.y
            }
        }

        /// Three-way comparison outcome.
        enum Cmp {
            /// left is smaller
            Lt
            /// left equals right
            Eq
            /// left is bigger
            Gt
        }

        /// Step of a range iteration: yielded element plus next state.
        struct IterStep {
            /// yielded element
            elem: Int
            /// next state
            state: Int
        }

        /// A half-open numeric range [lo, hi).
        struct Range {
            /// lower bound
            lo: Int
            /// upper bound
            hi: Int
        }

        impl Iterable<Int> for Range {
            /// Initial state of the iteration.
            function start(self: Range) -> Int {
                return self.lo
            }

            /// Advances the state machine: None ends, Some(step) yields and continues.
            function next(self: Range, state: Int) -> Option<IterStep> {
                if state < self.hi {
                    return Option.Some(IterStep(elem: state, state: state + 1))
                }
                return Option.None
            }
        }

        /// Classifies v against bound: smaller, equal or bigger.
        public function classify(v: Int, bound: Int) -> Cmp {
            if v < bound {
                return Cmp.Lt
            }
            if v == bound {
                return Cmp.Eq
            }
            return Cmp.Gt
        }

        /// Doubles the value when present, def otherwise.
        public function twiceOr(o: Option<Int>, def: Int) -> Int {
            match o {
                Option.Some(v) => v * 2
                Option.None => def
            }
        }

        /// First element of the array, def when empty.
        public function firstOr<T>(xs: Array<T>, def: T) -> T where T: Any {
            if len(xs) == 0 {
                return def
            }
            return xs[0]
        }

        /// Entry point exercising the declarations above.
        function main() -> Unit {
            let p: Point = Point(x: 3, y: 4)
            let a: Int = area(self: p)
            var total: Int = 0
            let r: Range = Range(lo: 1, hi: 5)
            for v: Int in r {
                total = total + v
            }
            var i: Int = 0
            while i < 3 {
                total = total + i
                i = i + 1
            }
            let c: Cmp = classify(7, 5)
            let d: Int = twiceOr(Option.Some(21), 0)
            let xs: Array<Int> = [1, 2, 3]
            let label: String = "example"
            let ratio: Float = 0.5
            let ok: Bool = true
            let first: Char = 'e'
            println(concat("area: ", str(a)))
            println(concat("total: ", str(total)))
            println(concat("twice: ", str(d)))
            println(concat("first: ", str(firstOr(xs, 0))))
        }

        test "classify" target: example.classify {
            let a: Cmp = classify(2, 5)
            let b: Cmp = classify(5, 5)
            let c: Cmp = classify(7, 5)
        }

        test "twiceOr" target: example.twiceOr {
            let a: Int = twiceOr(Option.Some(21), 0)
            let b: Int = twiceOr(Option.None, 7)
        }

        test "firstOr" target: example.firstOr {
            let xs: Array<Int> = [1, 2, 3]
            let a: Int = firstOr(xs, 0)
            let empty: Array<Int> = newArray(0)
            let b: Int = firstOr(empty, 9)
        }
    """.trimIndent()

    /** 工作区为空时装载缺省模块（项目首次启动）；非空则不动。返回本次装载的模块名。 */
    fun seedIfEmpty(workspace: Workspace): List<String> {
        if (workspace.moduleNames().isNotEmpty()) return emptyList()
        return listOf(MAIN, EXAMPLE).map { workspace.load(it).name }
    }
}
