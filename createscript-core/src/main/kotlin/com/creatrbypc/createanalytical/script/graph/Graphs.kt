package com.creatrbypc.createanalytical.script.graph

// 有向グラフ（呼び出し・生成の関係）の計算（第8.0.1段階）。節点は 0 から size - 1 の番号で、successors(v) は v から出る辺の先。
// 節点・辺が多くても JVM のスタックを使わないよう、再帰せずに書く。手間は節点の数 + 辺の数に比例する
internal object Graphs {
    // 強連結成分（Tarjan の方法を、呼び出しの積み重ねを配列で持って書いたもの）。結果は節点ごとの成分の番号で、成分は「行き先の成分が先」
    // （ある成分から辺で行ける別の成分の番号は、必ずその成分の番号より小さい）の順に 0 から番号を付ける
    fun stronglyConnectedComponents(size: Int, successors: (Int) -> IntArray): IntArray {
        val order = IntArray(size) { -1 }
        val low = IntArray(size)
        val component = IntArray(size) { -1 }
        val onStack = BooleanArray(size)
        val stack = IntArray(size)
        var stackSize = 0
        // たどっている途中の節点と、次に見る辺の位置
        val callNode = IntArray(size)
        val callEdge = IntArray(size)
        var callSize = 0
        var counter = 0
        var components = 0
        val edges = arrayOfNulls<IntArray>(size)
        fun edgesOf(node: Int): IntArray = edges[node] ?: successors(node).also { edges[node] = it }
        fun enter(node: Int) {
            order[node] = counter
            low[node] = counter
            counter++
            stack[stackSize++] = node
            onStack[node] = true
            callNode[callSize] = node
            callEdge[callSize] = 0
            callSize++
        }
        for (start in 0 until size) {
            if (order[start] != -1) continue
            enter(start)
            while (callSize > 0) {
                val node = callNode[callSize - 1]
                val next = edgesOf(node)
                val edge = callEdge[callSize - 1]
                if (edge < next.size) {
                    callEdge[callSize - 1] = edge + 1
                    val target = next[edge]
                    if (order[target] == -1) {
                        enter(target)
                    } else if (onStack[target]) {
                        low[node] = minOf(low[node], order[target])
                    }
                    continue
                }
                callSize--
                if (callSize > 0) {
                    val parent = callNode[callSize - 1]
                    low[parent] = minOf(low[parent], low[node])
                }
                if (low[node] == order[node]) {
                    while (true) {
                        val member = stack[--stackSize]
                        onStack[member] = false
                        component[member] = components
                        if (member == node) break
                    }
                    components++
                }
                edges[node] = null
            }
        }
        return component
    }

    // 循環の上にある節点（2 つ以上の節点の成分に属するか、自分自身への辺がある）
    fun onCycle(size: Int, successors: (Int) -> IntArray, component: IntArray): BooleanArray {
        val members = IntArray((component.maxOrNull() ?: -1) + 1)
        component.forEach { members[it]++ }
        return BooleanArray(size) { node -> members[component[node]] > 1 || successors(node).contains(node) }
    }

    // 循環の上にある節点か、辺をたどってそこへ行ける節点。成分の番号の小さい順（行き先が先）に決める
    fun reachesCycle(size: Int, successors: (Int) -> IntArray): BooleanArray {
        val component = stronglyConnectedComponents(size, successors)
        val cyclic = onCycle(size, successors, component)
        val count = (component.maxOrNull() ?: -1) + 1
        val reaches = BooleanArray(count)
        val byComponent = Array(count) { ArrayList<Int>() }
        for (node in 0 until size) byComponent[component[node]].add(node)
        for (c in 0 until count) {
            reaches[c] = byComponent[c].any { node -> cyclic[node] || successors(node).any { reaches[component[it]] } }
        }
        return BooleanArray(size) { reaches[component[it]] }
    }
}
