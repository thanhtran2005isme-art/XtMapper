package xtr.keymapper.server;

import android.app.ApplicationErrorReport;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import xtr.keymapper.ActivityObserver;
import xtr.keymapper.BuildConfig;
import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.R;
import xtr.keymapper.Utils;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.server.event.KeyEventHandler;

public class RemoteService extends IRemoteService.Stub {
    private String currentDevice = "";
    volatile InputService inputService;
    private OnKeyEventListener mOnKeyEventListener;
    boolean isWaylandClient = false;
    private ActivityObserverService activityObserverService;
    String nativeLibraryDir = System.getProperty("java.library.path");
    private View cursorView;
    private int TYPE_SECURE_SYSTEM_OVERLAY;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindowManager;
    private IBinder activeClientBinder;
    private DeathRecipient activeClientDeathRecipient;
    private long serverGeneration = 0;
    protected Context context;
    public static final String TAG = "xtmapper-server";
    boolean startedFromShell = false;

    public RemoteService(Context context) {
        loadLibraries();
        this.context = context;
        init();
    }

    private WindowManager getWindowManager(int displayId) {
        try {
            return prepareCursorOverlayWindow(displayId);
        } catch (Exception e) {
            cursorView = null;
            Log.e(TAG, "Unable to create cursor window on display " + displayId, e);

            Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (display != null) {
                try {
                    return context.createDisplayContext(display).getSystemService(WindowManager.class);
                } catch (Exception fallbackError) {
                    Log.e(TAG, "Unable to obtain display WindowManager " + displayId, fallbackError);
                }
            }
            return context.getSystemService(WindowManager.class);
        }
    }


    public void init() {
        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            nativeLibraryDir = ai.nativeLibraryDir;
            if(!isWaylandClient) new Thread(this::start_getevent).start();
            else mHandler.post(this::start_getevent);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }

        Looper.getMainLooper().getThread().setUncaughtExceptionHandler((t, e) -> {
            try {
                ApplicationErrorReport.CrashInfo crashInfo = new ApplicationErrorReport.CrashInfo(e);

                new ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                        new ComponentName(context, MainActivity.class).flattenToString(),
                        "--es", "data",
                        crashInfo.exceptionMessage + "\n" +
                                crashInfo.exceptionClassName + "\n" +
                                crashInfo.stackTrace + "\n" +
                                crashInfo.throwClassName + "\n" +
                                crashInfo.throwFileName + "\n" +
                                crashInfo.throwLineNumber + "\n" +
                                crashInfo.throwMethodName).start();

            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
            }
            System.exit(1);
        });
    }

    private void addCursorView() {
        if (cursorView == null) return;

        if(cursorView.isAttachedToWindow()) {
            cursorView.setVisibility(View.VISIBLE);
        } else {
            WindowManager.LayoutParams params = Utils.getPointerLayoutParams(TYPE_SECURE_SYSTEM_OVERLAY);
            try {
                mWindowManager.addView(cursorView, params);
            } catch (IllegalStateException e) { // A14 QPR3 issue https://gist.github.com/RikkaW/be3fe4178903702c54ec73b2fc1187fe
                cursorView = null;
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }


    public WindowManager prepareCursorOverlayWindow(int displayId) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException {
        TYPE_SECURE_SYSTEM_OVERLAY = WindowManager.LayoutParams.class.getField("TYPE_SECURE_SYSTEM_OVERLAY").getInt(null);

        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Display " + displayId + " is not available");
        }

        // Never mutate the base package Context. Samsung DeX may reconnect or resize
        // display 2 repeatedly; retaining a display/window context here makes later
        // package/resource operations use stale display state.
        Context displayContext = context.createDisplayContext(display);
        Context windowContext = displayContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowContext = displayContext.createWindowContext(display, TYPE_SECURE_SYSTEM_OVERLAY, null);
        }

        final WindowManager windowManager = windowContext.getSystemService(WindowManager.class);
        cursorView = null;

        // Shizuku/ADB servers run as shell (uid 2000) while the package resources
        // belong to the app uid. Inflating an app view from that process can throw
        // "calling package ... does not match caller uid". In that case use the
        // app-process callback in TouchPointer, which is also display-aware.
        if (android.os.Process.myUid() != context.getApplicationInfo().uid) {
            Log.i(TAG, "Using client-side cursor overlay for display " + displayId
                    + " because remote uid=" + android.os.Process.myUid()
                    + " app uid=" + context.getApplicationInfo().uid);
            return windowManager;
        }

        windowContext.setTheme(R.style.Theme_XtMapper);
        LayoutInflater layoutInflater = windowContext.getSystemService(LayoutInflater.class);
        cursorView = CursorBinding.inflate(layoutInflater).getRoot();

        Binder sWindowToken = new Binder();
        Method setDefaultTokenMethod = windowManager.getClass().getMethod("setDefaultToken", IBinder.class);
        setDefaultTokenMethod.invoke(windowManager, sWindowToken);
        return windowManager;
    }

    public static void loadLibraries() {
        System.loadLibrary("mouse_read");
        System.loadLibrary("mouse_cursor");
        System.loadLibrary("touchpad_direct");
        System.loadLibrary("touchpad_relative");
    }

    /**
     * Executes getevent command and processes the output or reads from stdin if wayland client
     */
    private void start_getevent() { try {
        final BufferedReader getevent;
        if (isWaylandClient) {
            getevent = new BufferedReader(new InputStreamReader(System.in));
        } else {
            getevent = Utils.geteventStream(nativeLibraryDir);
        }
        String line;
        while ((line = getevent.readLine()) != null) {
            String[] data = line.split(":"); // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
            if (addNewDevices(data)) {
                // Input events are read on a background thread while start/stop operations
                // run on the main handler. Keep a stable reference for the whole event.
                InputService service = inputService;
                if (service != null) try {
                    if (isWaylandClient && data[0].contains("wl_pointer"))
                        service.onWaylandMouseEvent(data[1]);

                    KeyEventHandler k = service.getKeyEventHandler();
                    if (!service.stopEvents) {
                        k.handleEvent(data[1]);
                    } else {
                        k.handleKeyboardShortcutEvent(data[1]);
                    }
                    if (mOnKeyEventListener != null) mOnKeyEventListener.onKeyEvent(line);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    } catch (Exception e){
        Log.e(TAG, e.getMessage(), e);
    }}

    /**
     * @param data split output of getevent command
     * @return true if output is valid for processing
     */
    private boolean addNewDevices(String[] data) {
        String[] input_event;
        if (data.length != 2) return false;
        String evdev = data[0];

        input_event = data[1].split("\\s+");
        if (isWaylandClient) return true;
        if( !currentDevice.equals(evdev) )
            if (input_event[1].equals("EV_REL")) {
                System.out.println("add mouse device: " + evdev);
                InputService service = inputService;
                if (service != null) service.openDevice(evdev);
                currentDevice = evdev;
            }
        return true;
    }

    /**
     * Called by client to start the remote server.
     *
     * @param profile  The keymap profile
     * @param keymapConfig Some configurations
     * @param cb  The instance used to callback to remote service
     * @param screenHeight Screen resolution (vertical)
     * @param screenWidth  Screen resolution (horizontal)
     */
    @Override
    public void startServer(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight, int displayId) throws RemoteException {
        mHandler.post(() -> {
            // Serialize every restart on the main looper. This is important on DeX,
            // where display/configuration callbacks can arrive in quick succession.
            stopServer(false);

            final long generation = ++serverGeneration;
            final IBinder clientBinder = cb != null ? cb.asBinder() : null;
            final DeathRecipient deathRecipient = () -> mHandler.post(() -> {
                if (generation == serverGeneration && activeClientBinder == clientBinder) {
                    Log.i(TAG, "Client binder died; stopping generation " + generation);
                    stopServer(false);
                }
            });

            if (clientBinder != null) {
                try {
                    clientBinder.linkToDeath(deathRecipient, 0);
                } catch (RemoteException e) {
                    Log.w(TAG, "Client died before startServer completed", e);
                    return;
                }
            }
            activeClientBinder = clientBinder;
            activeClientDeathRecipient = deathRecipient;

            mWindowManager = getWindowManager(displayId);

            if (keymapConfig.pointerMode != KeymapConfig.POINTER_SYSTEM) {
                addCursorView();
            } else {
                cursorView = null;
            }

            try {
                inputService = new InputService(profile, keymapConfig, cb, screenWidth, screenHeight, cursorView, isWaylandClient, displayId);
                if (!isWaylandClient) {
                    inputService.setMouseLock(true);
                    inputService.openDevice(currentDevice);
                }
            } catch (RemoteException e) {
                stopServer(false);
                throw new RuntimeException(e);
            }

            Log.i(TAG, "Server active on display " + displayId + " at " + screenWidth + "x" + screenHeight);

            // Launch the mapped app on the same selected display. Samsung DeX uses
            // display 2, and relying on the current focus can otherwise open the game
            // back on the phone (display 0).
            if (!profile.packageName.equals(BuildConfig.APPLICATION_ID) && keymapConfig.disableAutoProfiling) {
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(profile.packageName);
                if (launchIntent != null && launchIntent.getComponent() != null) try {
                    new ProcessBuilder("am", "start", "--display", String.valueOf(displayId),
                            "-a", "android.intent.action.MAIN", "-n",
                            launchIntent.getComponent().flattenToString()).start();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public void destroy() {
        mHandler.post(() -> stopServer(true));
    }

    @Override
    public void stopServer() {
        // Stopping a mapping must not kill the Shizuku/root user-service process.
        // Keeping the Binder alive lets EditorActivity reconnect without showing
        // a false "Not Activated" warning.
        mHandler.post(() -> stopServer(false));
    }

    private void stopServer(boolean exitProcess) {
        final InputService service = inputService;
        ++serverGeneration;

        IBinder oldClientBinder = activeClientBinder;
        DeathRecipient oldDeathRecipient = activeClientDeathRecipient;
        activeClientBinder = null;
        activeClientDeathRecipient = null;

        if (oldClientBinder != null && oldDeathRecipient != null) {
            try {
                oldClientBinder.unlinkToDeath(oldDeathRecipient, 0);
            } catch (Exception ignored) {
            }
        }

        if (service != null) {
            service.stopEvents = true;
            try {
                service.hideCursor();
            } catch (RuntimeException e) {
                Log.w(TAG, "Client disconnected while hiding cursor", e);
            }

            try {
                service.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to stop event handlers", e);
            }

            if (!isWaylandClient) {
                try {
                    service.stopMouse();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to stop mouse reader", e);
                }
                try {
                    service.stopTouchpad();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to stop touchpad", e);
                }
                try {
                    service.destroyUinputDev();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to destroy uinput cursor", e);
                }
            }

            if (inputService == service) {
                inputService = null;
            }
        }

        cursorView = null;
        mWindowManager = null;

        if (!startedFromShell && exitProcess) {
            System.exit(0);
        }
    }
    private final DeathRecipient mKeyEventListenerDeathRecipient = () -> mOnKeyEventListener = null;

    @Override
    public void registerOnKeyEventListener(OnKeyEventListener l) throws RemoteException {
        l.asBinder().linkToDeath(mKeyEventListenerDeathRecipient, 0);
        mOnKeyEventListener = l;
    }

    @Override
    public void unregisterOnKeyEventListener(OnKeyEventListener l)  {
        if (l != null) l.asBinder().unlinkToDeath(mKeyEventListenerDeathRecipient, 0);
        mOnKeyEventListener = null;
    }

    @Override
    public void registerActivityObserver(ActivityObserver callback) {
        if (activityObserverService != null)
            activityObserverService.stop();
        activityObserverService = new ActivityObserverService(callback);
    }

    @Override
    public void unregisterActivityObserver(ActivityObserver callback) {
        if (activityObserverService != null)
            activityObserverService.stop();
        activityObserverService = null;
    }

    /**
     * Used to temporary stop the keymapping.
     */
    @Override
    public void pauseMouse(){
        if (inputService != null)
            if (!inputService.stopEvents) inputService.pauseResumeKeymap();
    }

    @Override
    public void resumeMouse(){
        if (inputService != null)
            if (inputService.stopEvents)
                inputService.pauseResumeKeymap();
    }

    /**
     * Used to refresh by requesting new keymap from the user app
     */
    @Override
    public void reloadKeymap() {
        if (inputService != null) inputService.reloadKeymap();
    }

    @Override
    public boolean isActive() {
        return inputService != null;
    }

}
