package dev.xpramt.bahamutenhancer;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Only explains the module: the settings live in the player's own playback settings panel. */
public final class MainActivity extends Activity {
    private static final String TARGET_PACKAGE = "tw.com.gamer.android.animad";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.scroll);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        ((TextView) findViewById(R.id.title)).setText(R.string.app_name);
        ((TextView) findViewById(R.id.usage_title)).setText(R.string.usage_title);
        ((TextView) findViewById(R.id.usage_body)).setText(R.string.usage_body);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ImageView icon = findViewById(R.id.status_icon);
        TextView title = findViewById(R.id.status_title);
        TextView detail = findViewById(R.id.status_detail);
        title.setText(R.string.status_installed);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            icon.setImageResource(R.drawable.ic_check_circle);
            detail.setText(getString(R.string.status_target, info.versionName, (int) info.getLongVersionCode()));
        } catch (PackageManager.NameNotFoundException ignored) {
            icon.setImageResource(R.drawable.ic_warning);
            detail.setText(R.string.status_target_missing);
        }
    }
}
