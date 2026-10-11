package com.raah360.ar

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.raah360.core.ArFrame
import com.raah360.core.ImageSignature
import com.raah360.core.Vec2
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/** One detected landmark in AR coordinates. */
data class LandmarkObservation(
    val id: String,
    val arPos: Vec2,
    val arYaw: Double,
    val distanceM: Float,
)

/** Everything Raah360 needs from one ARCore frame. */
data class ArUpdate(
    val timeMs: Long,
    val tracking: TrackingState,
    val failure: TrackingFailureReason,
    /** Camera position on the AR floor plane (x, -z). */
    val camPos: Vec2?,
    /** User heading in the AR plane frame (compass style, degrees). */
    val heading: Double?,
    val cameraHeightM: Float,
    val landmarks: List<LandmarkObservation>,
    /** Visual signature of the centre of the current frame (only when requested). */
    val signature: FloatArray?,
)

data class LandmarkImage(val id: String, val bitmap: Bitmap, val widthM: Float)

/**
 * Owns the ARCore session and the camera preview surface.
 * Callbacks ([onUpdate]) run on the GL thread; marshal to the UI thread yourself.
 */
class ArEngine(private val activity: Activity) : GLSurfaceView.Renderer {

    val surface: GLSurfaceView = GLSurfaceView(activity).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArEngine)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    var session: Session? = null
        private set
    @Volatile var onUpdate: ((ArUpdate) -> Unit)? = null
    /** Compute a frame signature every N ms (0 = off). Used during arrival verification. */
    @Volatile var signatureEveryMs: Long = 0
    @Volatile var lastError: String? = null
        private set
    /** Route drawn on the real floor (navigation). Null draws nothing. */
    @Volatile var overlay: ArOverlay? = null
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val background = CameraBackground()
    private var installRequested = false
    private var viewportW = 0
    private var viewportH = 0
    @Volatile private var viewportChanged = false
    private var lastSigMs = 0L
    @Volatile private var pendingCapture: ((Bitmap?, FloatArray?) -> Unit)? = null
    private var landmarkImages: List<LandmarkImage> = emptyList()
    @Volatile var landmarkDbErrors: List<String> = emptyList()
        private set

    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { viewportChanged = true }
    }

    /**
     * Creates (if needed) and resumes the session. Returns null on success or a user-facing error.
     * Must be called from Activity/Lifecycle ON_RESUME with camera permission granted.
     */
    fun resume(): String? {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return "Installing Google Play Services for AR…"
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                    else -> Unit
                }
                val s = Session(activity)
                session = s
                configure(s, null)
                rebuildLandmarkDb()
            } catch (e: UnavailableArcoreNotInstalledException) {
                return fail("Please install Google Play Services for AR")
            } catch (e: UnavailableUserDeclinedInstallationException) {
                return fail("Google Play Services for AR is required for camera navigation")
            } catch (e: UnavailableApkTooOldException) {
                return fail("Please update Google Play Services for AR")
            } catch (e: UnavailableSdkTooOldException) {
                return fail("Please update this app")
            } catch (e: UnavailableDeviceNotCompatibleException) {
                return fail("This phone does not support ARCore. Use the Simulator instead.")
            } catch (e: Exception) {
                return fail("AR failed to start: ${e.message}")
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            return fail("Camera not available. Close other camera apps and try again.")
        } catch (e: Exception) {
            return fail("AR failed to resume: ${e.message}")
        }
        displayManager.registerDisplayListener(displayListener, null)
        viewportChanged = true
        surface.onResume()
        lastError = null
        return null
    }

    private fun fail(msg: String): String { lastError = msg; return msg }

    fun pause() {
        displayManager.unregisterDisplayListener(displayListener)
        surface.onPause()
        session?.pause()
    }

    fun destroy() {
        onUpdate = null
        session?.close()
        session = null
        worker.shutdown()
    }

    private fun configure(s: Session, db: AugmentedImageDatabase?) {
        val config = Config(s)
        config.focusMode = Config.FocusMode.AUTO
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.updateMode = Config.UpdateMode.BLOCKING
        if (db != null) config.augmentedImageDatabase = db
        s.configure(config)
    }

    /** Sets the landmark images ARCore should look for. Safe to call any time. */
    fun setLandmarks(images: List<LandmarkImage>) {
        landmarkImages = images
        rebuildLandmarkDb()
    }

    private fun rebuildLandmarkDb() {
        val s = session ?: return
        val images = landmarkImages
        worker.execute {
            val errors = ArrayList<String>()
            val db = AugmentedImageDatabase(s)
            for (img in images) {
                try {
                    db.addImage(img.id, img.bitmap, img.widthM)
                } catch (e: ImageInsufficientQualityException) {
                    errors += "${img.id}: not enough detail to track"
                } catch (e: Exception) {
                    errors += "${img.id}: ${e.message}"
                }
            }
            landmarkDbErrors = errors
            surface.queueEvent {
                try {
                    configure(s, db)
                } catch (e: Exception) {
                    Log.e(TAG, "configure failed", e)
                }
            }
        }
    }

    /**
     * Grabs the next camera frame as a display-oriented bitmap plus its visual signature
     * (computed exactly like the live verification signatures). Callback on main thread.
     */
    fun captureFrame(callback: (Bitmap?, FloatArray?) -> Unit) {
        pendingCapture = callback
    }

    // ---------------- GL renderer ----------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width; viewportH = height; viewportChanged = true
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            if (viewportChanged && viewportW > 0) {
                s.setDisplayGeometry(displayRotation(), viewportW, viewportH)
                viewportChanged = false
            }
            s.setCameraTextureName(background.textureId)
            val frame = s.update()
            background.draw(frame)
            val camera = frame.camera
            overlay?.let { o ->
                if (camera.trackingState == TrackingState.TRACKING) {
                    camera.getViewMatrix(viewMatrix, 0)
                    camera.getProjectionMatrix(projMatrix, 0, 0.05f, 80f)
                    val cp = camera.pose
                    o.draw(viewMatrix, projMatrix, cp.tx(), cp.tz())
                }
            }
            val now = System.currentTimeMillis()
            val tracking = camera.trackingState
            var camPos: Vec2? = null
            var heading: Double? = null
            var height = 0f
            val observations = ArrayList<LandmarkObservation>()
            if (tracking == TrackingState.TRACKING) {
                val pose = camera.displayOrientedPose
                camPos = ArFrame.toPlane(pose.tx(), pose.tz())
                height = pose.ty()
                heading = ArFrame.userHeading(pose.zAxis, pose.yAxis)
                for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
                    if (img.trackingState != TrackingState.TRACKING) continue
                    if (img.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
                    val c = img.centerPose
                    val dx = c.tx() - pose.tx(); val dy = c.ty() - pose.ty(); val dz = c.tz() - pose.tz()
                    observations += LandmarkObservation(
                        img.name,
                        ArFrame.toPlane(c.tx(), c.tz()),
                        ArFrame.landmarkHeading(c.yAxis, c.zAxis),
                        sqrt(dx * dx + dy * dy + dz * dz),
                    )
                }
            }
            var signature: FloatArray? = null
            val every = signatureEveryMs
            val capture = pendingCapture
            if ((every > 0 && now - lastSigMs >= every) || capture != null) {
                try {
                    val image = frame.acquireCameraImage()
                    try {
                        val rot = sensorToDisplayRotation(s)
                        if ((every > 0 && now - lastSigMs >= every) || capture != null) {
                            lastSigMs = now
                            val small = CameraImages.toArgb(image, 200, rot)
                            signature = ImageSignature.compute(small.pixels, small.width, small.height)
                        }
                        if (capture != null) {
                            pendingCapture = null
                            val big = CameraImages.toArgb(image, 1280, rot).toBitmap()
                            val sig = signature
                            main.post { capture(big, sig) }
                        }
                    } finally {
                        image.close()
                    }
                } catch (e: NotYetAvailableException) {
                    // try again next frame
                }
            }
            onUpdate?.invoke(
                ArUpdate(now, tracking, camera.trackingFailureReason, camPos, heading, height, observations, signature)
            )
        } catch (e: SessionPausedException) {
            // paused between frames; ignore
        } catch (e: Throwable) {
            Log.e(TAG, "frame failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.rotation
    }

    private var cachedRotation: Int? = null
    private fun sensorToDisplayRotation(s: Session): Int {
        cachedRotation?.let { return it }
        val r = try {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sensor = cm.getCameraCharacteristics(s.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val display = when (displayRotation()) {
                Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
            }
            (sensor - display + 360) % 360
        } catch (e: Exception) { 90 }
        cachedRotation = r
        return r
    }

    companion object {
        private const val TAG = "Raah360/AR"

        fun isSupported(context: Context): Boolean {
            val a = ArCoreApk.getInstance().checkAvailability(context)
            return a.isSupported || a.isTransient
        }
    }
}
