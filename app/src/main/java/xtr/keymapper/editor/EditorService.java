package xtr.keymapper.editor;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.appcompat.view.ContextThemeWrapper;

import xtr.keymapper.R;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.server.RemoteServiceHelper;

public class EditorService extends Service {
    private EditorUI editor;

    private void editorCallback() {
        editor = null;
        stopSelf();
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        String selectedProfile = intent != null
                ? intent.getStringExtra(EditorActivity.PROFILE_NAME)
                : null;
        if (selectedProfile == null) selectedProfile = "Default";

        int displayId = intent != null
                ? intent.getIntExtra(TouchPointer.DISPLAY_ID, Display.DEFAULT_DISPLAY)
                : Display.DEFAULT_DISPLAY;

        KeymapConfig keymapConfig = new KeymapConfig(this);
        if (keymapConfig.editorOverlay) {
            Context overlayContext = getOverlayContext(displayId);
            Context context = new ContextThemeWrapper(overlayContext, R.style.Theme_XtMapper);
            editor = new EditorUI(context, this::editorCallback, selectedProfile, EditorUI.START_EDITOR);

            RemoteServiceHelper.getInstance(EditorService.this, remoteService -> {
                if (remoteService == null || editor == null) return;
                try {
                    editor.registerOnKeyEventListener(remoteService);
                } catch (RemoteException e) {
                    Log.e("EditorService", e.getMessage(), e);
                }
            });

            editor.open(true);
        } else {
            Intent newIntent = new Intent(this, EditorActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            newIntent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                startActivity(newIntent, options.toBundle());
            } else {
                startActivity(newIntent);
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (editor != null) {
            editor.hideView();
            editor = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
