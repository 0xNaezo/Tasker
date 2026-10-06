package app.tasker.core.backup

import app.tasker.core.data.di.DataModule
import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/** Auto Backup must carry only the latest backup and the settings, never the live database (tech plan §16). */
class BackupRulesTest {
    private val expectedIncludes = setOf(
        "file:${BackupStore.DIR_NAME}/${BackupStore.LATEST_FILE}",
        // DataStoreFactory keeps `context.dataStoreFile(name)` in files/datastore/.
        "file:datastore/${DataModule.SETTINGS_FILE}",
    )

    private fun rules(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name")).documentElement

    /** `domain:path` of every [tag] element below this one. */
    private fun Element.rules(tag: String): Set<String> {
        val nodes = getElementsByTagName(tag)
        val elements = (0 until nodes.length).map { nodes.item(it) as Element }
        return elements.mapTo(HashSet()) { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }
    }

    private fun Element.child(tag: String): Element = getElementsByTagName(tag).item(0) as Element

    /** With includes present only they are copied, so the database is out exactly when no include names it. */
    private fun assertOnlyBackupAndSettings(section: Element) {
        assertThat(section.rules("include")).isEqualTo(expectedIncludes)
        assertThat(section.rules("include").none { it.startsWith("database:") }).isTrue()
        assertThat(section.rules("exclude")).isEmpty()
    }

    @Test
    fun `full backup content up to Android 11 holds the latest backup and the settings only`() {
        val root = rules("backup_rules.xml")

        assertThat(root.tagName).isEqualTo("full-backup-content")
        assertOnlyBackupAndSettings(root)
    }

    @Test
    fun `cloud backup and device transfer on Android 12+ hold the latest backup and the settings only`() {
        val root = rules("data_extraction_rules.xml")

        assertThat(root.tagName).isEqualTo("data-extraction-rules")
        assertOnlyBackupAndSettings(root.child("cloud-backup"))
        assertOnlyBackupAndSettings(root.child("device-transfer"))
    }
}
