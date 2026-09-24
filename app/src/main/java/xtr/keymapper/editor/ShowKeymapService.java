package xtr.keymapper.editor;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.WindowManager;

import androidx.appcompat.view.ContextThemeWrapper;

import xtr.keymapper.R;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.keymap.KeymapConfig;

public class ShowKeymapService extends Service {
    private EditorUI editorUi;

    public static void start(Context context, String selectedProfile) {
        int displayId = Display.DEFAULT_DISPLAY;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context.getDisplay() != null) {
            displayId = context.getDisplay().getDisplayId();
        }
        start(context, selectedProfile, displayId);
    }

    public static void start(Context context, String selectedProfile, int displayId) {
        Intent intent = new Intent(context, ShowKeymapService.class);
        intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);
        intent.putExtra(TouchPointer.DISPLAY_ID, displayId);
        context.startService(intent);
    }

    private Context getOverlayContext(int displayId) {
        Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) return this;

        Context displayContext = createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return displayContext.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
            );
        }
        return displayContext;
    }

    @Override
    public void onDestroy() {
        if (editorUi != null) {
            editorUi.hideView();
            editorUi = null;
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (editorUi != null) editorUi.hideView();

        String selectedProfile = intent != null
                ? intent.getStringExtra(EditorActivity.PROFILE_NAME)
                : null;
        if (selectedProfile == null) selectedProfile = "Default";

        int displayId = intent != null
                ? intent.getIntExtra(TouchPointer.DISPLAY_ID, Display.DEFAULT_DISPLAY)
                : Display.DEFAULT_DISPLAY;

        KeymapConfig keymapConfig = new KeymapConfig(this);
        Context overlayContext = getOverlayContext(displayId);
        Context context = new ContextThemeWrapper(overlayContext, R.style.Theme_XtMapper);
        editorUi = new EditorUI(context, editorCallback, selectedProfile, EditorUI.SHOW_KEYMAP_ONLY);
        editorUi.loadKeymapAfterView();
        editorUi.showControls(keymapConfig.showControlsOpacity);
        return START_STICKY;
    }

    private final EditorCallback editorCallback = () -> {
        editorUi = null;
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
