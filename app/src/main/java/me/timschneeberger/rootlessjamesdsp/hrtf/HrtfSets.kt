package me.timschneeberger.rootlessjamesdsp.hrtf

/**
 * The nine virtual speakers of the Binaural upmix, in the order the engine and
 * the reader use (Effects/binauralUpmix.c, libsofareader/sofa_extract.c).
 */
object BinauralSpeakers {
    const val COUNT = 9
    const val CENTRE = 0
    /** SOFA convention: azimuth counter-clockwise, +90 is the left. */
    val azimuth = floatArrayOf(0f, 30f, -30f, 60f, -60f, 110f, -110f, 45f, -45f)
    val elevation = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 40f, 40f)
}

/**
 * What the reader takes out of a .sofa file, untouched: the pair measured
 * nearest each speaker at the file's own rate, and the power average over
 * every direction.
 *
 * [hrir] holds [BinauralSpeakers.COUNT] pairs, left ear then right, [taps]
 * each. [diffuse] is the mean power at bins 0 .. n - 1 of an FFT of
 * 2 * (n - 1) points. [dirs] is the azimuth and elevation actually used, per
 * speaker.
 */
class RawHrtfSet(
    val fs: Float,
    val taps: Int,
    val hrir: FloatArray,
    val diffuse: FloatArray,
    val dirs: FloatArray,
    val measurements: Int,
    val title: String,
    val license: String,
    val database: String,
)

/**
 * A set ready for the engine: equalised (if asked), lined up, cut short and
 * levelled, still at its own rate [fs]. [cOff] is where the centre pair
 * peaks; the engine delays the plain signal by that much more so the two line
 * up.
 */
class PreparedHrtfSet(
    val fs: Float,
    val taps: Int,
    val cOff: Int,
    val hrir: FloatArray,
    val dirs: FloatArray,
    val evenedOut: Boolean,
    val title: String,
    val license: String,
    val database: String,
) {
    init {
        require(hrir.size == BinauralSpeakers.COUNT * 2 * taps)
        require(dirs.size == BinauralSpeakers.COUNT * 2)
        require(cOff in 0 until taps)
    }

    fun response(speaker: Int, ear: Int): FloatArray =
        hrir.copyOfRange((speaker * 2 + ear) * taps, (speaker * 2 + ear + 1) * taps)

    override fun equals(other: Any?): Boolean =
        other is PreparedHrtfSet && fs == other.fs && taps == other.taps &&
            cOff == other.cOff && hrir.contentEquals(other.hrir) &&
            dirs.contentEquals(other.dirs) && evenedOut == other.evenedOut &&
            title == other.title && license == other.license && database == other.database

    override fun hashCode(): Int = hrir.contentHashCode() * 31 + cOff
}
