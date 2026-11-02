package com.creatrbypc.createanalytical.script.diagnostic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticTest {
    private val origin = SourcePosition(0, 1, 1)

    private fun diagnostic(severity: DiagnosticSeverity) =
        Diagnostic(DiagnosticCode.LEX_INVALID_CHARACTER, severity, DiagnosticPhase.LEXER, origin)

    @Test
    fun truncateUtf16KeepsSurrogatePairs() {
        assertEquals("abc", truncateUtf16("abc", 3))
        assertEquals("ab", truncateUtf16("abc", 2))
        // "a😀" は 3 単位。2 単位で切るとペアの間になるため、ペアの手前で切る
        assertEquals("a", truncateUtf16("a😀", 2))
        assertEquals("a😀", truncateUtf16("a😀b", 3))
    }

    @Test
    fun stringArgumentLimit() {
        assertEquals(64, DiagnosticArgument.StringArgument.truncated("a".repeat(100)).value.length)
        assertEquals(63, DiagnosticArgument.StringArgument.truncated("a".repeat(63) + "😀").value.length)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticArgument.StringArgument("a".repeat(65)) }
    }

    @Test
    fun argumentCountLimit() {
        val five = List(5) { DiagnosticArgument.IntArgument(it) }
        assertThrows(IllegalArgumentException::class.java) {
            Diagnostic(DiagnosticCode.LEX_INVALID_CHARACTER, DiagnosticSeverity.ERROR, DiagnosticPhase.LEXER, origin, five)
        }
    }

    @Test
    fun collectorLimitPrefersErrors() {
        val collector = DiagnosticCollector()
        repeat(16) { collector.report(diagnostic(DiagnosticSeverity.WARNING)) }
        assertFalse(collector.hasErrors)
        assertFalse(collector.isTruncated)
        collector.report(diagnostic(DiagnosticSeverity.ERROR))
        assertTrue(collector.isTruncated)
        assertTrue(collector.hasErrors)
        assertEquals(16, collector.diagnostics.size)
        assertEquals(1, collector.diagnostics.count { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun droppedErrorsStillCount() {
        val collector = DiagnosticCollector()
        repeat(17) { collector.report(diagnostic(DiagnosticSeverity.ERROR)) }
        assertEquals(16, collector.diagnostics.size)
        assertTrue(collector.isTruncated)
        assertTrue(collector.hasErrors)
    }
}
