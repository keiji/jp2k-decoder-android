package dev.keiji.jp2k.datachannel

import org.junit.Assert.assertEquals
import org.junit.Test

class JSDataChannelTest {

    @Test
    fun testEscapeJs() {
        val input = "Hello 'World' \\ Test"
        val expected = "Hello \\'World\\' \\\\ Test"
        assertEquals(expected, input.escapeJs())
    }

    @Test
    fun testMinifyJs() {
        val input = """
            (() => {
                // This is a comment
                const x = 10;
                
                // Another comment
                const y = 20; // inline comments might remain if on line
                return x + y;
            })();
        """.trimIndent()

        val minified = input.minifyJs()

        val expected = """
            (() => {
            const x = 10;
            const y = 20; // inline comments might remain if on line
            return x + y;
            })();
        """.trimIndent()

        assertEquals(expected, minified)
    }

    @Test
    fun testMinifyJsRemovesBlankLinesAndLeadingSpaces() {
        val input = """
            
                let a = 1;   
                
                let b = 2;   
            
        """

        val expected = "let a = 1;\nlet b = 2;"
        assertEquals(expected, input.minifyJs())
    }
}
