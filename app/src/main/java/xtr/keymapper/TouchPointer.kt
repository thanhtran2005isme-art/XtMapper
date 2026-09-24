package xtr.keymapper

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xtr.keymapper.databinding.CursorBinding
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.editor.EditorService
import xtr.keymapper.editor.ShowKeymapService
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.profiles.ProfileSelector
import xtr.keymapper.server.RemoteServiceHelper

class TouchPointer : Service() {
    private val binder: IBinder = TouchPointerBinder()
    var activityCallback: MainActivityCallback? = null
    var mService: IRemoteService? = null
    var selectedProfile: String? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private var activityRemoteCallback = false
    private var mWindowManager: WindowManager? = null
    private var displayId = 0
    private var displayListener: DisplayListener? = null
    private var lastDisplayWidth = -1
    private var lastDisplayHeight = -1
    private val reconnectRunnable = Runnable {
        val profileName = selectedProfile ?: "Default"
        val profile = KeymapProfiles(this).getProfile(profileName, true)
        connectRemoteService(profile)
    }


    interface MainActivityCallback {
        fun updateCmdView1(line: String?)
        fun stopPointer()
    }


    inner class TouchPointerBinder : Binder() {
        val service: TouchPointer
            get() =// Return this instance of TouchPointer so clients can call public methods
                this@TouchPointer
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) {
            stopSelf()
            return super.onStartCommand(null, flags, startId)
        }

        // Launch default profile
        this.selectedProfile = i.getStringExtra(EditorActivity.PROFILE_NAME)
        if (this.selectedProfile == null) {
            this.selectedProfile = "Default"
        }

        // Resolve the target display before constructing notification/editor intents.
        // On Samsung DeX the selected desktop is typically display 2.
        this.displayId = i.getIntExtra(DISPLAY_ID, Display.DEFAULT_DISPLAY)

        val name = "Overlay"
        val channel =
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
                .setName(name).build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannel(channel)

        val keymapConfig = KeymapConfig(this)

        val pendingIntent: PendingIntent?

        if (keymapConfig.editorOverlay) {
            val intent = Intent(this, EditorService::class.java)
                .putExtra(DISPLAY_ID, displayId)
                .putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            val intent = Intent(this, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
                .putExtra(DISPLAY_ID, displayId)

            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(displayId)
                }.toBundle()
            } else null

            pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
                options
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        val notification = builder.setOngoing(true)
            .setContentTitle("Keymapper service running")
            .setContentText("Touch to launch editor")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }

        val displayManager = getSystemService(DisplayManager::class.java)
        val selectedDisplay = displayManager.getDisplay(displayId)
        if (selectedDisplay == null) {
            Log.e("TouchPointer", "Display $displayId is not available")
            activityCallback?.updateCmdView1("Display $displayId is not available")
            stopSelf()
            return START_NOT_STICKY
        }

        Point().also { selectedDisplay.getRealSize(it) }.let {
            lastDisplayWidth = it.x
            lastDisplayHeight = it.y
        }

        val keymapProfile = KeymapProfiles(this).getProfile(selectedProfile, true)
        connectRemoteService(keymapProfile)

        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = object : DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != this@TouchPointer.displayId) return

                val display = displayManager.getDisplay(displayId) ?: return
                val size = Point()
                display.getRealSize(size)

                // Samsung DeX emits many display-change callbacks for focus/window
                // changes even when the physical resolution did not change. Restarting
                // the remote server for every callback races Binder teardown/startup.
                if (size.x == lastDisplayWidth && size.y == lastDisplayHeight) return

                lastDisplayWidth = size.x
                lastDisplayHeight = size.y
                mHandler.removeCallbacks(reconnectRunnable)
                mHandler.postDelayed(reconnectRunnable, 250)
            }

            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == this@TouchPointer.displayId) {
                    mHandler.removeCallbacks(reconnectRunnable)
                    stopSelf()
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, mHandler)


        return super.onStartCommand(i, flags, startId)
    }

    fun launchProfile(profileName: String?) {
        this.selectedProfile = profileName
        val keymapProfile = KeymapProfiles(this).getProfile(selectedProfile, true)
        connectRemoteService(keymapProfile)
    }

    private fun connectRemoteService(profile: KeymapProfile) {
        activityCallback?.updateCmdView1("connecting to server..")

        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId)
        if (display == null) {
            Log.e("TouchPointer", "Display $displayId disappeared before server connection")
            activityCallback?.updateCmdView1("Display $displayId is not available")
            stopSelf()
            return
        }

        val size = Point()
        display.getRealSize(size) // TODO: getRealSize() deprecated in API level 31
        lastDisplayWidth = size.x
        lastDisplayHeight = size.y

        val overlayContext = displayContext
        mWindowManager = overlayContext?.getSystemService(WindowManager::class.java)

        RemoteServiceHelper.getInstance(this) { service: IRemoteService? ->
            if (service == null) {
                Log.e("TouchPointer", "Remote service is not available")
                activityCallback?.updateCmdView1("Remote service is not available")
                activityCallback?.stopPointer() ?: stopSelf()
                return@getInstance
            }

            mService = service
            val keymapConfig = KeymapConfig(this)
            try {
                if (keymapConfig.disableAutoProfiling) {
                    service.startServer(
                        profile,
                        keymapConfig,
                        mCallback,
                        size.x,
                        size.y,
                        displayId
                    )
                } else {
                    if (!activityRemoteCallback) {
                        service.registerActivityObserver(mActivityObserverCallback)
                        activityRemoteCallback = true
                    } else if (!profile.disabled) {
                        service.startServer(
                            profile,
                            keymapConfig,
                            mCallback,
                            size.x,
                            size.y,
                            displayId
                        )
                    }
                }
                if (keymapConfig.showControls) {
                    ShowKeymapService.start(this, selectedProfile, displayId)
                }
            } catch (e: Exception) {
                if (activityCallback != null) {
                    activityCallback?.updateCmdView1(e.toString())
                    activityCallback?.stopPointer()
                } else {
                    stopSelf()
                }
                Log.e("startServer", e.toString(), e)
            }
        }
    }

    override fun onDestroy() {
        mHandler.removeCallbacks(reconnectRunnable)
        displayListener?.let {
            try {
                getSystemService(DisplayManager::class.java).unregisterDisplayListener(it)
            } catch (_: Exception) {
            }
        }
        displayListener = null

        if (mService != null) try {
            mService!!.unregisterActivityObserver(mActivityObserverCallback)
            stopServer()
        } catch (e: Exception) {
            Log.e("stopServer", e.toString(), e)
        }
        mService = null
        activityCallback = null
        super.onDestroy()
    }

    @Throws(RemoteException::class)
    private fun stopServer() {
        stopService(Intent(this, ShowKeymapService::class.java))
        mService!!.stopServer()
    }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    val mCallback: IRemoteServiceCallback = object : IRemoteServiceCallback.Stub() {
        private var cursorView: View? = null

        override fun launchEditor() {
            val intent = Intent(this@TouchPointer, EditorService::class.java)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            intent.putExtra(DISPLAY_ID, displayId)
            startService(intent)
        }

        override fun alertMouseAimActivated() {
            // Notifying user that shooting mode was activated
            mHandler.post {
                Toast.makeText(
                    this@TouchPointer, R.string.mouse_aim_activated, Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun requestKeymapProfile(): KeymapProfile {
            return KeymapProfiles(this@TouchPointer).getProfile(selectedProfile, true)
        }

        override fun requestKeymapConfig(): KeymapConfig {
            return KeymapConfig(this@TouchPointer)
        }

        @UiThread
        override fun switchProfiles() {
            mHandler.post {
                val keymapProfiles = KeymapProfiles(this@TouchPointer)
                val keymapProfile = keymapProfiles.getProfile(selectedProfile, false)
                val application = keymapProfile.packageName

                if (keymapProfiles.getAllProfilesForApp(application).size == 1) {
                    Toast.makeText(
                        this@TouchPointer,
                        "Only one profile saved for $application",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@post
                }
                ProfileSelector.select(
                    this@TouchPointer,
                    { profile: String? ->
                        this@TouchPointer.selectedProfile = profile
                        // Reloading profile
                        connectRemoteService(keymapProfiles.getProfile(profile, true))
                    },
                    application
                )
            }
        }

        override fun enablePointer() {
            mHandler.post {
                if (cursorView != null) return@post

                val overlayContext = displayContext
                if (overlayContext == null) {
                    Log.e("TouchPointer", "Unable to create overlay context for display $displayId")
                    return@post
                }

                try {
                    val windowManager = overlayContext.getSystemService(WindowManager::class.java)
                    val view = CursorBinding.inflate(
                        LayoutInflater.from(
                            ContextThemeWrapper(overlayContext, R.style.Theme_XtMapper)
                        )
                    ).getRoot()

                    val params =
                        Utils.getPointerLayoutParams(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)

                    windowManager.addView(view, params)
                    mWindowManager = windowManager
                    cursorView = view
                    Log.i("TouchPointer", "Cursor overlay attached to display $displayId")
                } catch (e: Exception) {
                    cursorView = null
                    Log.e("TouchPointer", "Failed to add cursor overlay on display $displayId", e)
                    activityCallback?.updateCmdView1("Overlay failed on display $displayId: ${e.message}")
                }
            }
        }

        override fun disablePointer() {
            mHandler.post {
                val view = cursorView ?: return@post
                try {
                    if (view.isAttachedToWindow) {
                        mWindowManager?.removeView(view)
                    }
                } catch (e: Exception) {
                    Log.w("TouchPointer", "Failed to remove cursor overlay", e)
                } finally {
                    cursorView = null
                }
            }
        }

        override fun setCursorX(x: Int) {
            mHandler.post {
                if (cursorView != null) cursorView!!.x = x.toFloat()
            }
        }

        override fun setCursorY(y: Int) {
            mHandler.post {
                if (cursorView != null) cursorView!!.y = y.toFloat()
            }
        }
    }

    private val displayContext: Context?
        get() {
            val displayManager =
                getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(displayId) ?: return null
            val context: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                createDisplayContext(display).createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            } else {
                createDisplayContext(display)
            }
            return context
        }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private val mActivityObserverCallback: ActivityObserver = object : ActivityObserver.Stub() {
        private var lastPackageName: String? = null

        override fun onForegroundActivitiesChanged(packageName: String) {
            if (packageName == lastPackageName) return
            lastPackageName = packageName
            val context: Context = this@TouchPointer
            val keymapProfiles = KeymapProfiles(context)
            if (!keymapProfiles.profileExistsWithPackageName(packageName)) {
                // No profile found, prompt user to create a new profile
                mHandler.post {
                    ProfileSelector.showEnableProfileDialog(
                        context,
                        packageName
                    ) { enabled: Boolean ->
                        ProfileSelector.createNewProfileForApp(
                            context,
                            packageName,
                            enabled
                        ) { profile: String? ->
                            launchProfile(profile)
                        }
                    }
                }
            } else {
                // App specific profiles selection dialog
                mHandler.post {
                    ProfileSelector.select(context, { profile: String? ->
                        // Reloading profile
                        this@TouchPointer.selectedProfile = profile
                        val keymapProfile = keymapProfiles.getProfile(profile, true)
                        if (!keymapProfile.disabled) {
                            connectRemoteService(keymapProfile)
                            Toast.makeText(
                                this@TouchPointer,
                                "Keymapping enabled for $packageName",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            try {
                                mService!!.pauseMouse()
                            } catch (_: RemoteException) {
                            }
                            Toast.makeText(
                                this@TouchPointer,
                                "Keymapping disabled for $packageName",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }, packageName)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID: String = "pointer_service"
        const val DISPLAY_ID: String = "display_id"
    }
}
