package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine.PluginState

/**
 * What the root module's effect descriptor name says about it.
 *
 * Stock JamesDSP answers to the same uuid, and an older build of this fork's
 * module answers to it too, so the name is the only way to tell them apart.
 * The module is flashed separately from the app, so after an app update it is
 * often the previous build for a while.
 */
object EngineName {
    /**
     * The module version this app needs for everything it sends. Raise it,
     * together with the name in hal/JamesDspHalEffect.cpp and kEngineName in
     * magisk/aidl/src/EffectImpl.h, whenever the module
     * learns ids an older one would accept and then ignore.
     * 5: the spectral shaper (26023/26123) and the binaural upmix (26024/26124
     * and its head set, 9997/12002/10011/25004/30004).
     * 6: the bass exciter (26025/26125), dynamic system (26026/26126) and
     * differential surround (26027/26127).
     */
    const val REQUIRED_VERSION = 6

    /**
     * The module only works with the rooted app, so it calls itself
     * Rootful; builds up to 4.5.0 said Rootless, and a module is often a
     * build behind the app.
     */
    private val OURS = listOf("RootfulViPER4Android", "RootlessViPER4Android")
    private val VERSION = Regex("""engine v(\d+)""")

    /** The version in a name of this fork's engine, 0 when it carries none (the first builds). */
    fun version(name: String): Int =
        VERSION.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun state(name: String): PluginState = when {
        // Our own engine says so in its descriptor. Stock JamesDSP answers to
        // the same uuid, so without this the app reported Available, showed
        // every fork card, and sent parameter ids the stock engine has never
        // heard of. Unhandled ids are dropped silently at that end and the app
        // has no way to notice, so the user moved sliders that could not do
        // anything and nothing said why.
        OURS.any { name.contains(it) } ->
            if (version(name) >= REQUIRED_VERSION) PluginState.Available else PluginState.Outdated
        name.contains("v3") -> PluginState.Unsupported
        else -> PluginState.Foreign
    }
}
