package se.linusborjesson.scorespeaker.db

import app.cash.sqldelight.db.SqlDriver
import se.linusborjesson.scorespeaker.Log

/**
 * Platform handle for creating a [SqlDriver] backed by SQLite.
 *
 * - Android: constructed with a [android.content.Context]; database lives in
 *   the app-private dir (`databases/<name>.db`).
 * - Desktop / JVM tests: constructed with an optional file path; pass null
 *   for an in-memory database (the test default).
 *
 * Use [createDatabase] to obtain a fully-initialised [ScoreSpeakerDb].
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/**
 * Open the schema on [driverFactory]'s driver and return a typed database.
 *
 * Dev-grade schema guard: an on-disk database created by an older build may
 * not match the current schema (columns added/renamed/constrained). We
 * probe both directions with *generated* queries — which reference exactly
 * the columns the current code uses, so the check maintains itself:
 *
 *  - read probe: `selectAll` fails if a column the new code needs is
 *    missing (or a new NOT NULL meets legacy NULLs);
 *  - write probe: `insertShot` in a rolled-back transaction fails if the
 *    legacy table carries a stale NOT NULL column the new code no longer
 *    fills — without it that crash would surface at the first *real* shot,
 *    mid-session at the range.
 *
 * On failure: DROP the table and recreate it fresh. Deliberate pre-release
 * policy — historical shot data is expendable; a crash at the range is
 * not. It also means schema changes need no `.sqm` migration files yet
 * (the schema version stays at 1, so the driver's own migration path never
 * triggers). Replace this with real migrations when user history starts
 * mattering.
 */
fun createDatabase(driverFactory: DriverFactory): ScoreSpeakerDb {
    val driver = driverFactory.createDriver()
    val db = ScoreSpeakerDb(driver)
    try {
        db.shotQueries.selectAll().executeAsList()
        db.transaction {
            db.shotQueries.insertShot(
                shot_number = 0, mode = null, score = null,
                coords_x = null, coords_y = null,
                coords_x_direction = null, coords_y_direction = null,
                measurement_score = null, measurement_offset_x_rings = null,
                measurement_offset_y_rings = null, measurement_distance_rings = null,
                first_seen_at = 0, last_updated_at = 0,
            )
            rollback()
        }
    } catch (e: Exception) {
        Log.warn { "DB schema mismatch — dropping shot table and recreating (dev policy): ${e.message}" }
        driver.execute(null, "DROP TABLE IF EXISTS shot", 0)
        // Single-table schema; if more tables join, drop them above too.
        ScoreSpeakerDb.Schema.create(driver)
    }
    return db
}
