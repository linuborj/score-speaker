package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.settings.OffsetStyle

/**
 * On-demand spoken answers about the shot log — triggered by bound hardware
 * keys, not by pipeline events. Unlike the [Announcer] paths these ignore
 * the announce-toggles and have no dedup: the user pressed a key, they get
 * an answer, every time.
 *
 * Wording comes from [SpeechStrings] so the answers match the TTS voice's
 * language; offsets format via [OffsetSpeech] so queries and event
 * announcements sound alike and obey the same style/precision settings.
 */
object ShotQueries {

    /**
     * The most recent shot's number, score, and — when measured — its
     * error in the configured style, e.g. "Shot 7, 7 point 6, left 0
     * point 4" or "Shot 7, 7 point 6, 7 at 9 o'clock". A dead-centre
     * measurement says "centred"; a missing one says nothing rather than
     * guessing.
     */
    fun lastShotText(
        log: List<ShotRecord>,
        speech: SpeechStrings = EnglishSpeech,
        style: OffsetStyle = OffsetStyle.DIRECTIONS,
        withDecimal: Boolean = true,
    ): String {
        val shot = log.lastOrNull() ?: return speech.noShotsYet
        val parts = mutableListOf("${speech.shot} ${shot.shotNumber}")
        parts += shot.score?.let { OffsetSpeech.pronounce(it, speech) } ?: speech.noScoreRead
        shot.measurement?.let { m ->
            parts += OffsetSpeech.phrase(
                m.offsetXRings, m.offsetYRings, speech, style, withDecimal,
            ) ?: speech.centred
        }
        return parts.joinToString(", ")
    }

    /**
     * Mean green-dot offset over the last [count] shots that carry a
     * measurement, spoken as an aim correction hint — e.g.
     * "Average error last 5 shots: left 0 point 3, down 0 point 1".
     * The mean (not mean magnitude) is deliberate: it surfaces systematic
     * bias; random scatter cancels toward "centred".
     *
     * Deliberately unaffected by the offset style/precision settings:
     * a mean position has no single ring value to hang a clock call on,
     * and a typical bias is well under a ring — whole-ring rounding would
     * read a real 0.4-ring drift as "centred". Direction words with
     * decimals, always.
     */
    fun averageErrorText(
        log: List<ShotRecord>,
        count: Int = 5,
        speech: SpeechStrings = EnglishSpeech,
    ): String {
        val measurements = log.takeLast(count).mapNotNull { it.measurement }
        if (measurements.isEmpty()) return speech.noMeasurementsYet
        val avgX = measurements.map { it.offsetXRings }.average()
        val avgY = measurements.map { it.offsetYRings }.average()
        val where = OffsetSpeech.phrase(
            avgX, avgY, speech,
            style = OffsetStyle.DIRECTIONS, withDecimal = true,
        ) ?: speech.centred
        return "${speech.averageErrorTemplate.format(measurements.size)}: $where"
    }
}
