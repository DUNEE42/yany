package com.xinyu.companion.domain

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ArchitectureBoundaryTest {
    @Test
    fun `domain does not import data ui or dependency injection packages`() {
        val domainRoot = File("src/main/java/com/xinyu/companion/domain")
        assertThat(domainRoot.isDirectory).isTrue()

        val forbiddenImport = Regex(
            """import\s+com\.xinyu\.companion\.(data|ui|di)(\.|\s)""",
        )
        val violations = domainRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                file.readText()
                    .lineSequence()
                    .firstOrNull(forbiddenImport::containsMatchIn)
                    ?.let { "${file.relativeTo(domainRoot)}: $it" }
            }
            .toList()

        assertThat(violations).isEmpty()
    }
}
