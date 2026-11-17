package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.apilisting.ApiListing
import com.creatrbypc.createanalytical.script.apilistingfixture.ApiListingFixtureHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

// A3: the public API of createscript-core on the JVM must match api/createscript-core.api (docs/api/api-surface.md).
// The file changes only when the tests run with CREATESCRIPT_API_UPDATE=1; review its diff like any API change.
class ApiListingTest {
    @Test
    fun publicApiMatchesTheListing() {
        val listingFile = File(System.getProperty("createscript.api.listing") ?: fail("createscript.api.listing is not set"))
        val classes = (System.getProperty("createscript.api.classes") ?: fail("createscript.api.classes is not set"))
            .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
        val actual = ApiListing.listing("createscript-core", classes, setOf("com.creatrbypc.createanalytical.script.InternalCreateScriptApi"))
        if (System.getenv(ApiListing.UPDATE_VARIABLE) == "1") {
            listingFile.parentFile.mkdirs()
            listingFile.writeText(actual)
            println("Wrote ${listingFile.name}")
            return
        }
        if (!listingFile.isFile) fail<Unit>("${listingFile.name} is missing. Make it with ${ApiListing.UPDATE_VARIABLE}=1 ./gradlew :createscript-core:test --tests '*ApiListingTest*'")
        val expected = listingFile.readText().replace("\r\n", "\n")
        if (expected != actual) {
            fail<Unit>(
                "The public API of createscript-core differs from ${listingFile.name}. If the change is intended, run " +
                    "${ApiListing.UPDATE_VARIABLE}=1 ./gradlew :createscript-core:test --tests '*ApiListingTest*' and review the diff.\n" +
                    ApiListing.difference(expected, actual),
            )
        }
        assertTrue(actual.contains("// opt-in"), "the listing marks the Internal API")
    }

    // R2.5: the marker of a property is on a synthetic `$annotations` holder. For a getter the holder has the getter's
    // name; for a constant or @JvmField field it has the name of the getter the property would have. Before R2.5 the
    // listing missed the marker of a top-level constant (FUNCTION_TYPE_NAME). Lists only the fixture classes.
    @Test
    fun markersOnConstantsAndPropertiesAreListed(@TempDir directory: Path) {
        val testClasses = File(ApiListingFixtureHolder::class.java.protectionDomain.codeSource.location.toURI())
        val packagePath = "com/creatrbypc/createanalytical/script/apilistingfixture"
        val fixtures = File(testClasses, packagePath).listFiles { file -> file.name.endsWith(".class") }.orEmpty().sortedBy { it.name }
        assertEquals(listOf("ApiListingFixtureHolder.class", "ApiListingFixtureKt.class"), fixtures.map { it.name })
        val copy = directory.resolve(packagePath).toFile().apply { mkdirs() }
        fixtures.forEach { it.copyTo(File(copy, it.name)) }
        val listing = ApiListing.listing("createscript-core", listOf(directory.toFile()), setOf("com.creatrbypc.createanalytical.script.InternalCreateScriptApi"))

        fun line(name: String): String {
            val found = listing.lines().filter { Regex("""[ .]${Regex.escape(name)}[;(]""").containsMatchIn(it) }
            assertEquals(1, found.size, "one line for $name in:\n$listing")
            return found.single()
        }
        for (name in listOf("MARKED_TOP_LEVEL_CONSTANT", "MARKED_CONSTANT", "getMarkedProperty", "markedField", "isMarkedFlag")) {
            assertTrue(line(name).contains("// opt-in"), "$name is marked opt-in in:\n$listing")
        }
        for (name in listOf("PLAIN_TOP_LEVEL_CONSTANT", "PLAIN_CONSTANT", "getPlainProperty")) {
            assertTrue(!line(name).contains("opt-in"), "$name is not marked opt-in in:\n$listing")
        }
    }
}
