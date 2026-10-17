package me.timschneeberger.rootlessjamesdsp.model

import android.os.Bundle
import androidx.databinding.ObservableArrayList
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getSerializableAs
import timber.log.Timber
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

/**
 * Result of parsing an EqualizerAPO format string.
 * @param skippedFilters number of unsupported filter lines that were skipped
 * @param preampDb the parsed Preamp value in dB (0.0 if not present)
 */
data class ApoImportResult(
    val skippedFilters: Int,
    val preampDb: Double
)

class ParametricEqBandList : ObservableArrayList<ParametricEqBand>() {
    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfGain = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfQ = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    init {
        dfFreq.maximumFractionDigits = 2
        dfGain.maximumFractionDigits = 6
        dfQ.maximumFractionDigits = 4
    }

    /**
     * Internal serialization format for SharedPreferences.
     * Format: "PEQ: freq gain q type; freq gain q type; ..."
     */
    fun serialize(): String {
        val sb = StringBuilder("PEQ: ")
        for (band in this) {
            sb.append("${dfFreq.format(band.frequency)} ${dfGain.format(band.gain)} ${dfQ.format(band.q)} ${band.filterType.code}")
            // A fifth field only when it says something. Every band saved
            // before per-channel existed has four, and a reader that sees four
            // takes BOTH, so old presets and old builds both stay readable.
            if (band.channel != ParametricEqChannel.BOTH) sb.append(" ${band.channel.code}")
            sb.append("; ")
        }
        return sb.toString()
    }

    /**
     * Parse the internal serialization format.
     */
    fun deserialize(str: String) {
        this.clear()

        str.replace("PEQ:", "")
            .replace("\n", " ")
            .split(";")
            .map { it.trim() }
            .filter(String::isNotBlank)
            .forEach { s ->
                val parts = s.split(" ").filter(String::isNotBlank)
                val freq = parts.getOrNull(0)?.toDoubleOrNull()
                val gain = parts.getOrNull(1)?.toDoubleOrNull()
                val q = parts.getOrNull(2)?.toDoubleOrNull()
                val type = parts.getOrNull(3)?.toIntOrNull()
                val channel = parts.getOrNull(4)?.toIntOrNull()

                if (freq != null && gain != null && q != null && type != null) {
                    this.add(ParametricEqBand(freq, gain, q,
                        ParametricEqFilterType.fromCode(type),
                        ParametricEqChannel.fromCode(channel ?: 0)))
                }
            }
    }

    /**
     * Export to EqualizerAPO format.
     * Example:
     *   Preamp: 0 dB
     *   Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
     *   Filter 2: ON LSC Fc 100 Hz Gain 5.0 dB Q 0.71
     */
    fun toApoString(preampDb: Double = 0.0): String {
        val sb = StringBuilder()
        sb.appendLine("Preamp: ${dfGain.format(preampDb)} dB")
        // A "Channel:" line applies to every filter after it, so one is only
        // written where the channel actually changes, and none at all when
        // every band is on both. That keeps an ordinary preset byte-identical
        // to what this used to produce, which matters because other tools read
        // these files too.
        var current: ParametricEqChannel? = null
        var n = 0
        for (band in this) {
            if (band.channel != current) {
                if (band.channel != ParametricEqChannel.BOTH || current != null)
                    sb.appendLine("Channel: ${band.channel.apoLabel}")
                current = band.channel
            }
            n++
            sb.appendLine(
                "Filter $n: ON ${band.filterType.apoLabel} Fc ${dfFreq.format(band.frequency)} Hz Gain ${dfGain.format(band.gain)} dB Q ${dfQ.format(band.q)}"
            )
        }
        return sb.toString()
    }

    /**
     * Parse EqualizerAPO format.
     * Supports lines like:
     *   Preamp: -3 dB
     *   Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
     *   Filter 2: ON LSC Fc 100 Hz Gain 5.0 dB Q 0.71
     * Parses Preamp value and filter bands.
     * Returns [ApoImportResult] with the parsed preamp and number of skipped filters.
     */
    fun fromApoString(text: String): ApoImportResult {
        this.clear()
        var skipped = 0
        var preampDb = 0.0

        val filterRegex = Regex(
            """Filter\s+\d+:\s+ON\s+(\S+)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+([-\d.]+)\s+dB\s+Q\s+([\d.]+)""",
            RegexOption.IGNORE_CASE
        )
        val preampRegex = Regex(
            """Preamp:\s*([-\d.]+)\s*dB""",
            RegexOption.IGNORE_CASE
        )
        // Applies to every filter that follows it, until the next one.
        val channelRegex = Regex("""Channel:\s*(\S+)""", RegexOption.IGNORE_CASE)
        var channel = ParametricEqChannel.BOTH

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue
            }

            // Parse preamp line
            if (trimmed.startsWith("Preamp", ignoreCase = true)) {
                val preampMatch = preampRegex.find(trimmed)
                if (preampMatch != null) {
                    preampDb = preampMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    // Clamp to valid range
                    preampDb = preampDb.coerceIn(-30.0, 0.0)
                } else {
                    Timber.d("fromApoString: could not parse preamp line: $trimmed")
                }
                continue
            }

            if (trimmed.startsWith("Channel", ignoreCase = true)) {
                val found = channelRegex.find(trimmed)?.groupValues?.get(1)
                val parsed = found?.let { ParametricEqChannel.fromApoLabel(it) }
                if (parsed == null) {
                    // An unknown channel name is worse than a bad filter line:
                    // silently treating it as both ears would apply a
                    // correction meant for one side to the other as well. Fall
                    // back to both, but say so.
                    Timber.w("fromApoString: unknown channel '$found', treating the bands after it as both")
                    channel = ParametricEqChannel.BOTH
                } else {
                    channel = parsed
                }
                continue
            }

            val match = filterRegex.find(trimmed)
            if (match == null) {
                Timber.d("fromApoString: skipping unrecognized line: $trimmed")
                continue
            }

            val typeStr = match.groupValues[1]
            val freq = match.groupValues[2].toDoubleOrNull() ?: continue
            val gain = match.groupValues[3].toDoubleOrNull() ?: continue
            val q = match.groupValues[4].toDoubleOrNull() ?: continue

            val filterType = ParametricEqFilterType.fromApoLabel(typeStr)
            if (filterType == null) {
                Timber.d("fromApoString: unsupported filter type '$typeStr', skipping")
                skipped++
                continue
            }

            this.add(ParametricEqBand(freq, gain, q, filterType, channel))
        }

        return ApoImportResult(skippedFilters = skipped, preampDb = preampDb)
    }

    fun fromBundle(bundle: Bundle) {
        this.clear()

        val freq = bundle.getDoubleArray(STATE_FREQ) ?: return
        val gain = bundle.getDoubleArray(STATE_GAIN) ?: return
        val q = bundle.getDoubleArray(STATE_Q) ?: return
        val types = bundle.getIntArray(STATE_TYPE) ?: return
        val uuids = bundle.getSerializableAs<Array<UUID>>(STATE_UUID)
        // Absent in a bundle saved before this existed, which reads as BOTH.
        val channels = bundle.getIntArray(STATE_CHANNEL)

        val count = minOf(freq.size, gain.size, q.size, types.size)
        for (i in 0 until count) {
            this.add(
                ParametricEqBand(
                    freq[i], gain[i], q[i],
                    ParametricEqFilterType.fromCode(types[i]),
                    ParametricEqChannel.fromCode(channels?.getOrNull(i) ?: 0),
                    uuids?.getOrNull(i) ?: UUID.randomUUID()
                )
            )
        }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        val freqArr = DoubleArray(this.size)
        val gainArr = DoubleArray(this.size)
        val qArr = DoubleArray(this.size)
        val typeArr = IntArray(this.size)
        val channelArr = IntArray(this.size)
        val uuidArr = arrayListOf<UUID>()

        for ((i, band) in this.withIndex()) {
            freqArr[i] = band.frequency
            gainArr[i] = band.gain
            qArr[i] = band.q
            typeArr[i] = band.filterType.code
            channelArr[i] = band.channel.code
            uuidArr.add(band.uuid)
        }

        bundle.putDoubleArray(STATE_FREQ, freqArr)
        bundle.putDoubleArray(STATE_GAIN, gainArr)
        bundle.putDoubleArray(STATE_Q, qArr)
        bundle.putIntArray(STATE_TYPE, typeArr)
        bundle.putIntArray(STATE_CHANNEL, channelArr)
        bundle.putSerializable(STATE_UUID, uuidArr.toTypedArray())
        return bundle
    }

    companion object {
        private const val STATE_FREQ = "peq_freq"
        private const val STATE_GAIN = "peq_gain"
        private const val STATE_Q = "peq_q"
        private const val STATE_TYPE = "peq_type"
        private const val STATE_UUID = "peq_uuid"
        private const val STATE_CHANNEL = "peq_channel"
    }
}
