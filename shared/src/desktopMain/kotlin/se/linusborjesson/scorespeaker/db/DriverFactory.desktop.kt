package se.linusborjesson.scorespeaker.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/**
 * Desktop / JVM driver factory.
 *
 * @param databasePath File path for the database, or null for an in-memory DB
 *   (the default for tests and ad-hoc desktop runs that don't need
 *   persistence).
 */
actual class DriverFactory(
    private val databasePath: String? = null,
) {
    actual fun createDriver(): SqlDriver {
        val url = databasePath?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY
        val driver = JdbcSqliteDriver(url, Properties())
        // Statements are IF NOT EXISTS, so this is a no-op on a current
        // database. On a *mismatched* old-format one it can still fail
        // (e.g. index over a missing column) — swallow that here and let
        // createDatabase's schema guard drop + recreate the table.
        runCatching { ScoreSpeakerDb.Schema.create(driver) }
        return driver
    }
}
