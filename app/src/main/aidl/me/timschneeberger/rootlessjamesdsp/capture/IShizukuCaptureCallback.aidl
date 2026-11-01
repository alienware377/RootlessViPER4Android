package me.timschneeberger.rootlessjamesdsp.capture;

/**
 * Sent by the Shizuku capture helper back to the app when the capture ends on
 * its own: the app stopped reading, the recorder failed, or Shizuku is
 * removing the helper. A stop the app asked for is not reported.
 */
interface IShizukuCaptureCallback {
    oneway void onStopped(String reason) = 1;
}
