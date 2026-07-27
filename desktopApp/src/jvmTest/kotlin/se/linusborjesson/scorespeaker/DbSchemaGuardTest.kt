package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.db.DriverFactory
import se.linusborjesson.scorespeaker.db.createDatabase
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.ShotRecorder
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals

/**
 * The dev-grade schema guard: a database created by an older build whose
 * `shot` table no longer matches the current schema is dropped and
 * recreated on open — historical data is expendable pre-release, a crash
 * at the range is not.
 */
class DbSchemaGuardTest {

    @Test
    fun `mismatched shot table is dropped and recreated`() {
        val file = File.createTempFile("schema-guard", ".db").also { it.delete() }
        try {
            // Simulate an old build's database: same table name, wrong shape.
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE TABLE shot (id INTEGER PRIMARY KEY, legacy_col TEXT NOT NULL)")
                    s.execute("INSERT INTO shot (legacy_col) VALUES ('old data')")
                }
            }

            val db = createDatabase(DriverFactory(file.absolutePath))

            // Old data is gone, and the current schema round-trips.
            assertEquals(emptyList(), db.shotQueries.selectAll().executeAsList())
            val tracker = ShotTracker(confirmationFrames = 1)
            ShotRecorder(db).record(
                tracker.process(Reading(1000, mapOf("D" to ScoreShotValue("1", "P", "9.5")))),
            )
            val rows = db.shotQueries.selectAll().executeAsList()
            assertEquals(1, rows.size)
            assertEquals("9.5", rows[0].score)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `stale NOT NULL extra column is caught by the write probe`() {
        // Read-direction probing alone would pass here: every column the new
        // code selects exists. But the stale NOT NULL column would make the
        // first real INSERT fail — mid-session, at the range.
        val file = File.createTempFile("schema-write-probe", ".db").also { it.delete() }
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { s ->
                    s.execute(
                        """CREATE TABLE shot (
                           id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                           shot_number INTEGER NOT NULL,
                           mode TEXT, score TEXT,
                           coords_x TEXT, coords_y TEXT,
                           coords_x_direction TEXT, coords_y_direction TEXT,
                           measurement_score REAL,
                           measurement_offset_x_rings REAL,
                           measurement_offset_y_rings REAL,
                           measurement_distance_rings REAL,
                           first_seen_at INTEGER NOT NULL,
                           last_updated_at INTEGER NOT NULL,
                           stale_required TEXT NOT NULL)""",
                    )
                }
            }

            val db = createDatabase(DriverFactory(file.absolutePath))

            val tracker = ShotTracker(confirmationFrames = 1)
            ShotRecorder(db).record(
                tracker.process(Reading(1000, mapOf("D" to ScoreShotValue("1", "P", "8.8")))),
            )
            assertEquals(1, db.shotQueries.selectAll().executeAsList().size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `matching database opens with data intact`() {
        val file = File.createTempFile("schema-keep", ".db").also { it.delete() }
        try {
            val first = createDatabase(DriverFactory(file.absolutePath))
            val tracker = ShotTracker(confirmationFrames = 1)
            ShotRecorder(first).record(
                tracker.process(Reading(1000, mapOf("D" to ScoreShotValue("1", "P", "10.4")))),
            )

            // Reopen: same schema — nothing may be dropped.
            val second = createDatabase(DriverFactory(file.absolutePath))
            val rows = second.shotQueries.selectAll().executeAsList()
            assertEquals(1, rows.size)
            assertEquals("10.4", rows[0].score)
        } finally {
            file.delete()
        }
    }
}
