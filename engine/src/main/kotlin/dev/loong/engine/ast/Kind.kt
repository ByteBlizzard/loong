package dev.loong.engine.ast

/**
 * 节点种类。清单与语义必须与 doc/spec/ast-schema-v1.md 一致；
 * 排列顺序无语义，只增不改名。
 */
enum class Kind {
    // 声明类
    Module,
    Depends,
    Function,
    Param,
    TypeParam,
    Struct,
    Field,
    Interface,
    MethodSig,
    Impl,
    Enum,
    Variant,
    Const,
    Test,
    DocComment,
    ExternalClass,
    ExternalMethod,

    // 语句类
    Block,
    Let,
    Assign,
    If,
    IfLet,
    While,
    For,
    Match,
    MatchArm,
    Return,
    Break,
    Continue,
    ExprStmt,

    // 表达式类
    Call,
    Arg,
    Binary,
    Unary,
    Literal,
    MapEntry,
    Path,
    FieldAccess,
    Index,
    Spawn,
    Join,

}
