package com.creatrbypc.createanalytical.project

/**
 * A position in a project file, used by [ManifestResult] and [LockfileResult] to point at a value of
 * `createscript.json` or `createscript.lock`. A position that is not known is line 1, column 1.
 *
 * @property line the line, starting at 1.
 * @property column the column in UTF-16 code units, starting at 1.
 */
public data class FilePosition(val line: Int, val column: Int)
