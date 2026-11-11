package com.creatrbypc.createanalytical.registrytool

// .github/CODEOWNERS と maintainers.json の整合（S8.3 Phase 5、P4-M1）。
// GitHub の「コードオーナーの承認」は、作成者自身の承認を数えない。コードオーナーが運営責任者 1 人だけだと、運営責任者の PR は
// マージできない（P4-M1）。そこで、コードオーナーは「すべてのメンテナー」とし、運営責任者の関与は check-approvals が確かめる。
// 規則（これ以外の行は、空行とコメント（#）だけ）:
// - メンテナーがいる: `* @login1 @login2 …` の 1 行だけで、所有者の集合が maintainers.json のメンテナーと完全に同じ（大文字小文字は区別しない）
// - メンテナーがいない（立ち上げの前）: `* @<lead>` の 1 行だけ
// これにより、登録が開いている間（メンテナー 3 人以上・承認 2 人）は、どの作成者にも、作成者以外のコードオーナーが 2 人以上いる
object CodeOwners {
    const val PATH = ".github/CODEOWNERS"
    private val OWNER = Regex("@([A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?)")

    fun problems(bytes: ByteArray?, maintainers: Maintainers): List<String> {
        if (bytes == null) return listOf("$PATH is missing; every maintainer must be a code owner (GOVERNANCE.md)")
        val text = bytes.toString(Charsets.UTF_8)
        val rules = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (rules.size != 1) return listOf("$PATH must have exactly one rule, \"* @maintainer …\", listing every maintainer (it has ${rules.size})")
        val parts = rules.single().split(Regex("\\s+"))
        if (parts.first() != "*") return listOf("$PATH: the only rule must be for \"*\" (all files)")
        val owners = parts.drop(1).map { part -> OWNER.matchEntire(part)?.groupValues?.get(1)?.lowercase() ?: return listOf("$PATH: ${Report.safe(part)} is not a GitHub user (@login); teams are not used") }
        if (owners.size != owners.toSet().size) return listOf("$PATH lists an owner twice")
        val expected = if (maintainers.maintainers.isEmpty()) setOf(maintainers.lead.lowercase()) else maintainers.maintainers.map { it.login.lowercase() }.toSet()
        val problems = ArrayList<String>()
        (expected - owners.toSet()).sorted().forEach { problems += "$PATH does not list the maintainer @$it" }
        (owners.toSet() - expected).sorted().forEach { problems += "$PATH lists @$it, who is not a maintainer" }
        return problems
    }
}
