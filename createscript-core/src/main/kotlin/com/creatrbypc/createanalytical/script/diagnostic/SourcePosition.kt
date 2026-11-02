package com.creatrbypc.createanalytical.script.diagnostic

// ソース上の位置。offset は 0 始まり、line・column は 1 始まりで、いずれも UTF-16 コード単位で数える
data class SourcePosition(val offset: Int, val line: Int, val column: Int)
