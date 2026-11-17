package com.creatrbypc.createanalytical.script.diagnostic

/**
 * A position in source text, counted in UTF-16 code units.
 *
 * @property offset from the start of the text, starting at 0.
 * @property line starting at 1.
 * @property column starting at 1.
 */
public data class SourcePosition(val offset: Int, val line: Int, val column: Int)
