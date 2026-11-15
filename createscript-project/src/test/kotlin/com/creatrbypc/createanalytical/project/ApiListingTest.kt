package com.creatrbypc.createanalytical.project

import com.creatrbypc.createanalytical.apilisting.ApiListing
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

// A3: the public API of createscript-project on the JVM must match api/createscript-project.api (docs/api/api-surface.md).
// The file changes only when the tests run with CREATESCRIPT_API_UPDATE=1; review its diff like any API change.
class ApiListingTest {
    @Test
    fun publicApiMatchesTheListing() {
        val listingFile = File(System.getProperty("createscript.api.listing") ?: fail("createscript.api.listing is not set"))
        val classes = (System.getProperty("createscript.api.classes") ?: fail("createscript.api.classes is not set"))
            .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
        val actual = ApiListing.listing("createscript-project", classes, setOf("com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi"))
        if (System.getenv(ApiListing.UPDATE_VARIABLE) == "1") {
            listingFile.parentFile.mkdirs()
            listingFile.writeText(actual)
            println("Wrote ${listingFile.name}")
            return
        }
        if (!listingFile.isFile) fail<Unit>("${listingFile.name} is missing. Make it with ${ApiListing.UPDATE_VARIABLE}=1 ./gradlew :createscript-projectest --tests '*ApiListingTest*'")
        val expected = listingFile.readText().replace("\r\n", "\n")
        if (expected != actual) {
            fail<Unit>(
                "The public API of createscript-project differs from ${listingFile.name}. If the change is intended, run " +
                    "${ApiListing.UPDATE_VARIABLE}=1 ./gradlew :createscript-projectest --tests '*ApiListingTest*' and review the diff.\n" +
                    ApiListing.difference(expected, actual),
            )
        }
        assertTrue(actual.contains("// opt-in"), "the listing marks the Internal API")
    }
}
