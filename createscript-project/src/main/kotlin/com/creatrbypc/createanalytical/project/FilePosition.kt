package com.creatrbypc.createanalytical.project

/**
 * A position in a project file: [line] and [column] start at 1 (columns count UTF-16 code units).
 *
 * Used by [ManifestResult] and [LockfileResult] to point at a value of `createscript.json` or
 * `createscript.lock`. A position that is not known is line 1, column 1.
 */
public data class FilePosition(val line: Int, val column: Int)
