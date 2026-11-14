package com.alienware377.viper4android.voiceaddon;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * What the installer's Open button shows: this is part of RootlessViPER4Android, there is
 * nothing to do here, and a way into the app. There is no launcher icon.
 */
public final class AddonActivity extends Activity {
    /** The app this add-on serves, release build first. */
    private static final String[] APP_PACKAGES = {
            "com.alienware377.viper4android.rootless",
            "com.alienware377.viper4android.rootless.debug",
    };

    private static final String RELEASES =
            "https://github.com/alienware377/RootlessViPER4Android/releases/latest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_addon);
        ((TextView) findViewById(R.id.version)).setText(
                getString(R.string.addon_version, BuildConfig.VERSION_NAME, VoiceAddon.API_VERSION));
        ((TextView) findViewById(R.id.licences)).setText(licences());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Looked up again each time: the app may have been installed meanwhile.
        Intent launch = appLaunchIntent();
        Button open = findViewById(R.id.open_app);
        open.setText(launch != null ? R.string.addon_open_app : R.string.addon_get_app);
        open.setOnClickListener(v -> start(launch != null
                ? launch : new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))));
    }

    private Intent appLaunchIntent() {
        for (String packageName : APP_PACKAGES) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) return intent;
        }
        return null;
    }

    private void start(Intent intent) {
        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException ignored) {
            // No browser; the page itself still says where to look.
        }
    }

    private String licences() {
        try (InputStream in = getResources().openRawResource(R.raw.licences)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }
}
