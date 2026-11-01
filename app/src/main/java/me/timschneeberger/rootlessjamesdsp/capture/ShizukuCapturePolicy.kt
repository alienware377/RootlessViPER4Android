package me.timschneeberger.rootlessjamesdsp.capture

/**
 * The decisions behind the Shizuku capture method, kept free of Android calls
 * so they can be unit tested.
 */
object ShizukuCapturePolicy {

    /** Android 13. Older versions do not give the shell user MODIFY_AUDIO_ROUTING. */
    const val MIN_SDK = 33

    /** The shell user. Shizuku started through ADB or wireless debugging runs as this. */
    const val SHELL_UID = 2000

    /** Why Shizuku capture can or cannot be used right now. */
    enum class State {
        /** Everything is in place. */
        Ready,
        /** The user has not chosen the Shizuku method. */
        NotSelected,
        /** Only the rootless build captures audio at all. */
        NotRootless,
        /** Android 12 or older. */
        AndroidTooOld,
        /** Shizuku is not installed, or installed but not started. */
        NotRunning,
        /** A Shizuku from before version 11, which has no helper services. */
        ShizukuTooOld,
        /** Shizuku is running but has not given this app permission. */
        NoPermission,
        /** Shizuku runs as root rather than as the shell user. Not supported yet. */
        RunningAsRoot,
    }

    fun state(
        selected: Boolean,
        isRootless: Boolean,
        sdkInt: Int,
        binderAlive: Boolean,
        preV11: Boolean,
        permissionGranted: Boolean,
        serverUid: Int,
    ): State = when {
        !selected -> State.NotSelected
        !isRootless -> State.NotRootless
        sdkInt < MIN_SDK -> State.AndroidTooOld
        !binderAlive -> State.NotRunning
        preV11 -> State.ShizukuTooOld
        !permissionGranted -> State.NoPermission
        serverUid != SHELL_UID -> State.RunningAsRoot
        else -> State.Ready
    }

    /** What to do when processing is being turned on. */
    enum class StartAction {
        /** Start with Shizuku, no prompt. */
        UseShizuku,
        /** Use screen capture, the user never asked for anything else. */
        UseStandard,
        /** Shizuku was wanted but cannot be used: say why, then use screen capture. */
        FallBackToStandard,
        /** Shizuku was wanted, cannot be used, and fallback is off: say why and stay off. */
        GiveUp,
    }

    fun startAction(state: State, fallbackAllowed: Boolean): StartAction = when (state) {
        State.Ready -> StartAction.UseShizuku
        State.NotSelected, State.NotRootless -> StartAction.UseStandard
        else -> if (fallbackAllowed) StartAction.FallBackToStandard else StartAction.GiveUp
    }

    /** What to do when a Shizuku capture fails or ends without being asked to. */
    enum class LossAction {
        /** Still starting up, someone is waiting: switch to screen capture. */
        FallBackToStandard,
        /** Still starting up, fallback is off: say why and stay off. */
        GiveUp,
        /**
         * It was already running. Stop and leave a notification to turn it back
         * on. A screen capture prompt out of nowhere would be worse than the
         * music pausing, which is what players do when the route goes away.
         */
        StopAndNotify,
    }

    fun lossAction(duringStartup: Boolean, fallbackAllowed: Boolean): LossAction = when {
        !duringStartup -> LossAction.StopAndNotify
        fallbackAllowed -> LossAction.FallBackToStandard
        else -> LossAction.GiveUp
    }

    /**
     * Which apps the loopback route takes. Android refuses a rule that both
     * matches and excludes apps by uid, so exactly one of the two lists is
     * used.
     *
     * Our own uid can never be in the route: our processed output would be
     * taken again and fed back in, a loop that howls and doubles everything.
     * Screen capture had a second guard for that (our track refuses capture),
     * but a pure loopback route ignores capture policies, so the rule is the
     * only thing standing in the way.
     *
     * An allowlist that ends up empty is treated as "not set up yet", the same
     * as the screen capture method does: capture everything except us, rather
     * than nothing at all, which would sound like a broken app.
     */
    fun mixUids(allowlistMode: Boolean, listedUids: Collection<Int>, ownUid: Int): MixUids {
        if (allowlistMode) {
            val match = listedUids.filter { it != ownUid }.distinct()
            if (match.isNotEmpty())
                return MixUids(match.toIntArray(), IntArray(0))
        }
        val exclude = ((if (allowlistMode) emptyList() else listedUids) + ownUid).distinct()
        return MixUids(IntArray(0), exclude.toIntArray())
    }

    class MixUids(val match: IntArray, val exclude: IntArray) {
        override fun toString() =
            "match=${match.joinToString(",")} exclude=${exclude.joinToString(",")}"
    }
}
