package dev.vvivid.newsfeed.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {
    /** Each colored piece of [code] in [language], as its text and kind. */
    private fun pieces(
        code: String,
        language: String?,
    ) = highlightCode(code, language).map { code.substring(it.start, it.end) to it.kind }

    private fun kindsOf(
        code: String,
        language: String,
    ) = pieces(code, language).toMap()

    @Test
    fun `go keywords, calls, built-in types, strings, numbers and comments`() {
        val code =
            """
            func better(a, b *invoice, paid *big.Int) bool {
                if c := gap(a, paid).Cmp(gap(b, paid)); c != 0 { // compare gaps
                    return inv, "fuzzy"
                }
                return nil, `raw`
            }
            """.trimIndent()

        val kinds = kindsOf(code, "go")

        assertEquals(CodeTokenKind.Keyword, kinds["func"])
        assertEquals(CodeTokenKind.Keyword, kinds["if"])
        assertEquals(CodeTokenKind.Keyword, kinds["return"])
        assertEquals(CodeTokenKind.Function, kinds["better"])
        assertEquals(CodeTokenKind.Function, kinds["gap"])
        assertEquals(CodeTokenKind.Function, kinds["Cmp"])
        assertEquals(CodeTokenKind.Type, kinds["bool"])
        assertEquals(CodeTokenKind.Type, kinds["nil"])
        assertEquals(CodeTokenKind.Text, kinds["\"fuzzy\""])
        assertEquals(CodeTokenKind.Text, kinds["`raw`"])
        assertEquals(CodeTokenKind.Number, kinds["0"])
        assertEquals(CodeTokenKind.Comment, kinds["// compare gaps"])
        // Go capitalizes exported names, not types, so a plain name stays uncolored.
        assertNull(kinds["Int"])
        assertNull(kinds["paid"])
    }

    @Test
    fun `strings keep escaped quotes and stop at the end of the line`() {
        val code = "s := \"say \\\"hi\\\"\" // done\nt := \"open"

        val texts = pieces(code, "go").filter { it.second == CodeTokenKind.Text }.map { it.first }

        assertEquals(listOf("\"say \\\"hi\\\"\"", "\"open"), texts)
    }

    @Test
    fun `sql keywords ignore case and use dash comments`() {
        val kinds = kindsOf("INSERT INTO keys (key) VALUES ('k1') -- once\nON CONFLICT DO NOTHING;", "sql")

        assertEquals(CodeTokenKind.Keyword, kinds["INSERT"])
        assertEquals(CodeTokenKind.Keyword, kinds["NOTHING"])
        assertEquals(CodeTokenKind.Text, kinds["'k1'"])
        assertEquals(CodeTokenKind.Comment, kinds["-- once"])
    }

    @Test
    fun `capitalized names are types in kotlin and python comments start with a hash`() {
        assertEquals(CodeTokenKind.Type, kindsOf("val list: List<String> = listOf()", "kotlin")["List"])
        assertEquals(CodeTokenKind.Function, kindsOf("val list: List<String> = listOf()", "kotlin")["listOf"])
        assertEquals(CodeTokenKind.Comment, kindsOf("x = 1  # one", "py")["# one"])
        assertEquals(CodeTokenKind.Type, kindsOf("x = None", "python")["None"])
    }

    @Test
    fun `code in an unknown language or none stays one color`() {
        assertTrue(highlightCode("if x { return }", null).isEmpty())
        assertTrue(highlightCode("if x { return }", "brainfuck").isEmpty())
    }

    @Test
    fun `tokens never overlap and stay inside the code`() {
        val code = "/* open comment\nfunc f() { return \"x\" }"
        val tokens = highlightCode(code, "go")

        tokens.zipWithNext { a, b -> assertTrue(a.end <= b.start) }
        assertTrue(tokens.all { it.start >= 0 && it.end <= code.length && it.start < it.end })
        assertEquals(CodeTokenKind.Comment, tokens.single().kind)
    }

    @Test
    fun `languages are shown by name`() {
        assertEquals("Go", codeLanguageName("go"))
        assertEquals("TypeScript", codeLanguageName("ts"))
        assertEquals("haskell", codeLanguageName("haskell"))
        assertNull(codeLanguageName(null))
    }

    @Test
    fun `a fenced block keeps its language, lowercased`() {
        val blocks = parseArticleBody("```Go title=x\nfunc f() {}\n```\n\n    indented\n", "")

        assertEquals(listOf(BodyBlock.CodeBlock("func f() {}", "go"), BodyBlock.CodeBlock("indented", null)), blocks)
    }
}
