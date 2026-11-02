package io.github.hridoyrocks.dak

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The version comes from gradle.properties; the docs must name the same one. */
class VersionTest {
    private fun read(name: String) = File("..", name).readText()

    @Test
    fun theChangelogAndTheReadmeNameThisVersion() {
        assertTrue("CHANGELOG.md has no entry for ${Dak.VERSION}", Regex("(?m)^## ${Regex.escape(Dak.VERSION)} - ").containsMatchIn(read("CHANGELOG.md")))
        assertTrue("README.md installs another version", read("README.md").contains(":${Dak.VERSION}\")"))
    }
}
