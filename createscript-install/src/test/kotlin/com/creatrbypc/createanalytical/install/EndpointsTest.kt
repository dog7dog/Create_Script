package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

// S8.2 Phase 2: 本番の接続先（通信をしない単体テスト）
class EndpointsTest {
    private val github = Endpoints.GITHUB

    // 本番の設定そのもの（退行を防ぐ）: HTTPS だけ、決まったホストとポート 443 だけ、IP アドレスは不可
    @Test
    fun productionSettings() {
        assertTrue(github.https)
        assertFalse(github.ipLiterals)
        assertEquals(setOf(443), github.ports)
        assertEquals(setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"), github.hosts)
        assertEquals(URI("https://github.com"), github.download)
        assertEquals(URI("https://api.github.com"), github.api)
    }

    @Test
    fun allowsOnlyTheGitHubHosts() {
        for (url in listOf(
            "https://github.com/o/r/releases/download/v1.0.0/createscript-package.zip",
            "https://api.github.com/repos/o/r/releases/tags/v1.0.0",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1?sp=r&sig=x",
            "https://objects.githubusercontent.com/x",
            "https://github.com:443/x",
            "https://GitHub.com/x",
        )) assertNull(github.problem(URI(url)), url)
        val refused = listOf(
            "http://github.com/x", "ftp://github.com/x", "file:///etc/passwd", "https://github.com.evil.example/x", "https://evilgithub.com/x",
            "https://gist.github.com/x", "https://github.com./x", "https://user@github.com/x", "https://user:pass@github.com/x", "https://github.com:8443/x",
            "https://github.com:80/x", "https://140.82.112.3/x", "https://[::1]/x", "https://2130706433/x", "https://0x7f000001/x", "https://127.0.0.1/x",
            "https://github.com/x#fragment", "//github.com/x", "/relative/path",
        )
        for (url in refused) assertNotNull(github.problem(URI(url)), url)
    }

    // トークンを付けてよいのは api.github.com だけ（github.com・アセットの配信先には付けない）
    @Test
    fun tokenOnlyForTheApi() {
        assertTrue(github.mayCarryToken(URI("https://api.github.com/repos/o/r/releases/tags/v1.0.0")))
        for (url in listOf("https://github.com/x", "https://release-assets.githubusercontent.com/x", "http://api.github.com/x", "https://api.github.com:8443/x", "https://api.github.com.evil.example/x")) {
            assertFalse(github.mayCarryToken(URI(url)), url)
        }
    }
}
