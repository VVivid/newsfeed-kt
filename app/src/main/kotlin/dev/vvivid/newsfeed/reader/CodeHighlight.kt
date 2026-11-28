package dev.vvivid.newsfeed.reader

/** What a piece of code is, for coloring it. */
internal enum class CodeTokenKind {
    Keyword,

    /** Built-in types and constants such as `bool`, `nil` or `true`, and type names. */
    Type,

    /** A name that is called, such as `gap` in `gap(a, paid)`. */
    Function,

    /** A string or character literal. */
    Text,
    Number,
    Comment,

    /** Operators, brackets and separators. */
    Punctuation,
}

/** [kind] covers the characters of the code from [start] up to [end], exclusive. */
internal data class CodeToken(
    val kind: CodeTokenKind,
    val start: Int,
    val end: Int,
)

/** The name a code block's language is shown under, such as "Go" for "go"; null for none. */
internal fun codeLanguageName(language: String?): String? {
    if (language == null) return null
    return LanguageNames[language] ?: language
}

/**
 * The colored pieces of [code] in [language], in order. Code in a language this list doesn't know,
 * or without one, has none: it shows in one color, as before.
 *
 * This is a reader's highlighter, not a parser: it knows each language's comments, strings,
 * numbers and keywords, calls a name followed by `(` a function, and in languages that capitalize
 * type names, a capitalized name a type.
 */
internal fun highlightCode(
    code: String,
    language: String?,
): List<CodeToken> {
    val syntax = language?.let(Languages::get) ?: return emptyList()
    val tokens = mutableListOf<CodeToken>()
    var i = 0
    while (i < code.length) {
        val c = code[i]
        val lineComment = syntax.lineComments.firstOrNull { code.startsWith(it, i) }
        val block = syntax.blockComment
        when {
            lineComment != null -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                tokens += CodeToken(CodeTokenKind.Comment, i, end)
                i = end
            }

            block != null && code.startsWith(block.first, i) -> {
                val close = code.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) code.length else close + block.second.length
                tokens += CodeToken(CodeTokenKind.Comment, i, end)
                i = end
            }

            c in syntax.quotes -> {
                val end = stringEnd(code, i)
                tokens += CodeToken(CodeTokenKind.Text, i, end)
                i = end
            }

            c.isDigit() -> {
                var end = i + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')) end++
                tokens += CodeToken(CodeTokenKind.Number, i, end)
                i = end
            }

            c.isLetter() || c == '_' || (c == '$' && syntax.dollarNames) -> {
                var end = i + 1
                while (end < code.length &&
                    (code[end].isLetterOrDigit() || code[end] == '_' || (code[end] == '$' && syntax.dollarNames))
                ) {
                    end++
                }
                syntax.kindOf(code.substring(i, end), code, end)?.let { tokens += CodeToken(it, i, end) }
                i = end
            }

            c in PUNCTUATION -> {
                var end = i + 1
                while (end < code.length && code[end] in PUNCTUATION && syntax.lineComments.none { code.startsWith(it, end) }) end++
                tokens += CodeToken(CodeTokenKind.Punctuation, i, end)
                i = end
            }

            else -> {
                i++
            }
        }
    }
    return tokens
}

/** Where the string starting at [start] ends: after its closing quote, or at the line's end. */
private fun stringEnd(
    code: String,
    start: Int,
): Int {
    val quote = code[start]
    var i = start + 1
    while (i < code.length) {
        when {
            code[i] == '\\' && quote != '`' -> i += 2
            code[i] == quote -> return i + 1
            code[i] == '\n' && quote != '`' -> return i
            else -> i++
        }
    }
    return code.length
}

private const val PUNCTUATION = "{}[]()<>=!+-*/%&|^~?:;,."

/** How one language writes comments, strings and names. */
private class Syntax(
    keywords: String,
    types: String = "",
    val lineComments: List<String> = listOf("//"),
    val blockComment: Pair<String, String>? = "/*" to "*/",
    val quotes: String = "\"'",
    /** Type names start with a capital letter, as in Kotlin and Java, but not Go. */
    val capitalizedTypes: Boolean = false,
    val ignoreCase: Boolean = false,
    val dollarNames: Boolean = false,
) {
    private val keywords = words(keywords)
    private val types = words(types)

    private fun words(list: String): Set<String> =
        list
            .split(' ')
            .filter {
                it.isNotEmpty()
            }.map { if (ignoreCase) it.lowercase() else it }
            .toSet()

    fun kindOf(
        word: String,
        code: String,
        end: Int,
    ): CodeTokenKind? {
        val key = if (ignoreCase) word.lowercase() else word
        var next = end
        while (next < code.length && code[next] == ' ') next++
        return when {
            key in keywords -> CodeTokenKind.Keyword
            key in types -> CodeTokenKind.Type
            next < code.length && code[next] == '(' -> CodeTokenKind.Function
            capitalizedTypes && word.first().isUpperCase() -> CodeTokenKind.Type
            else -> null
        }
    }
}

private val CLike =
    Syntax(
        keywords =
            "if else for while do switch case default break continue return goto struct union enum typedef sizeof " +
                "static const extern class public private protected new delete this try catch throw namespace using " +
                "virtual override template typename import package func fn let var mut guard defer in is as",
        types = "void bool char short int long float double unsigned signed size_t string String true false null nil NULL nullptr self",
        capitalizedTypes = true,
    )

private val Kotlin =
    Syntax(
        keywords =
            "as break class continue do else for fun if in interface is object package return super this throw try " +
                "typealias val var when while by catch constructor finally import init where override private public " +
                "protected internal abstract open data sealed enum companion suspend inline lateinit const value",
        types = "true false null Unit Any Nothing",
        capitalizedTypes = true,
    )

private val Script =
    Syntax(
        keywords =
            "break case catch class const continue debugger default delete do else export extends finally for function " +
                "if import in instanceof new return super switch this throw try typeof var void while with yield let static " +
                "async await of from as interface type enum implements private public protected readonly declare",
        types = "string number boolean any unknown never undefined null true false bigint object symbol",
        quotes = "\"'`",
        capitalizedTypes = true,
        dollarNames = true,
    )

private val Languages: Map<String, Syntax> =
    buildMap {
        put(
            "go",
            Syntax(
                keywords =
                    "break case chan const continue default defer else fallthrough for func go goto if import interface " +
                        "map package range return select struct switch type var",
                types =
                    "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 " +
                        "uint16 uint32 uint64 uintptr any comparable nil true false iota",
                quotes = "\"'`",
            ),
        )
        listOf("kotlin", "kt", "kts").forEach { put(it, Kotlin) }
        listOf("java", "c", "h", "cpp", "c++", "cc", "cs", "csharp", "swift").forEach { put(it, CLike) }
        listOf("javascript", "js", "jsx", "typescript", "ts", "tsx", "mjs").forEach { put(it, Script) }
        val python =
            Syntax(
                keywords =
                    "and as assert async await break class continue def del elif else except finally for from global if " +
                        "import in is lambda nonlocal not or pass raise return try while with yield match case",
                types = "None True False int str float bool list dict set tuple bytes self cls",
                lineComments = listOf("#"),
                blockComment = null,
                capitalizedTypes = true,
            )
        listOf("python", "py").forEach { put(it, python) }
        val rust =
            Syntax(
                keywords =
                    "as break const continue crate else enum extern fn for if impl in let loop match mod move mut pub ref " +
                        "return static struct super trait type unsafe use where while async await dyn",
                types = "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str self Self true false",
                capitalizedTypes = true,
            )
        listOf("rust", "rs").forEach { put(it, rust) }
        put(
            "sql",
            Syntax(
                keywords =
                    "select from where insert into values update set delete create table index on and or not is in exists " +
                        "join left right inner outer full cross group by order having limit offset as primary key foreign " +
                        "references unique default begin commit rollback transaction returning conflict do nothing case when " +
                        "then else end distinct union all with alter add drop constraint check if",
                types =
                    "null true false int integer bigint smallint text varchar char boolean bool timestamp timestamptz date " +
                        "time numeric decimal real double serial bigserial uuid json jsonb blob",
                lineComments = listOf("--"),
                quotes = "'\"",
                ignoreCase = true,
            ),
        )
        val shell =
            Syntax(
                keywords = "if then else elif fi for while until do done case esac in function return export local readonly select",
                types = "true false",
                lineComments = listOf("#"),
                blockComment = null,
                dollarNames = true,
            )
        listOf("bash", "sh", "shell", "zsh", "console").forEach { put(it, shell) }
        put("json", Syntax(keywords = "", types = "true false null", lineComments = emptyList(), blockComment = null, quotes = "\""))
        val yaml = Syntax(keywords = "", types = "true false null yes no", lineComments = listOf("#"), blockComment = null)
        listOf("yaml", "yml").forEach { put(it, yaml) }
    }

private val LanguageNames =
    mapOf(
        "go" to "Go",
        "kotlin" to "Kotlin",
        "kt" to "Kotlin",
        "kts" to "Kotlin",
        "java" to "Java",
        "c" to "C",
        "h" to "C",
        "cpp" to "C++",
        "c++" to "C++",
        "cc" to "C++",
        "cs" to "C#",
        "csharp" to "C#",
        "swift" to "Swift",
        "javascript" to "JavaScript",
        "js" to "JavaScript",
        "jsx" to "JavaScript",
        "mjs" to "JavaScript",
        "typescript" to "TypeScript",
        "ts" to "TypeScript",
        "tsx" to "TypeScript",
        "python" to "Python",
        "py" to "Python",
        "rust" to "Rust",
        "rs" to "Rust",
        "sql" to "SQL",
        "bash" to "Shell",
        "sh" to "Shell",
        "shell" to "Shell",
        "zsh" to "Shell",
        "console" to "Shell",
        "json" to "JSON",
        "yaml" to "YAML",
        "yml" to "YAML",
    )
