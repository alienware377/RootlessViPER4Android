package me.timschneeberger.rootlessjamesdsp.capture;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import me.timschneeberger.rootlessjamesdsp.capture.IShizukuCaptureCallback;

/**
 * The capture helper that Shizuku runs for us as the shell user (uid 2000).
 * See ShizukuCaptureUserService for what each call does.
 */
interface IShizukuCaptureService {
    /** Called by Shizuku itself when it removes the helper. Drops the route. */
    void destroy() = 16777114;

    /** The uid the helper runs as. Only 2000 (shell) is supported. */
    int getUid() = 1;

    /**
     * Registers the loopback route and starts sending the captured audio down
     * the returned pipe. Any capture already running is dropped first.
     * matchUids and excludeUids are never both non-empty.
     */
    ParcelFileDescriptor start(in Bundle config, in int[] matchUids, in int[] excludeUids,
            IBinder appToken, IShizukuCaptureCallback callback) = 2;

    /** Drops the route and stops capturing. Safe to call at any time. */
    void stop() = 3;

    boolean isCapturing() = 4;

    /** One line describing the current state, for logs and bug reports. */
    String getStatus() = 5;
}
