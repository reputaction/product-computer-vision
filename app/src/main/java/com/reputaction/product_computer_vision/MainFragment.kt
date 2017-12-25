package com.reputaction.product_computer_vision


import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v13.app.FragmentCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.*
import kotlinx.android.synthetic.main.fragment_main.*
import java.util.*
import java.util.Collections.max
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


/**
 * A simple [Fragment] subclass.
 *
 */
class MainFragment : Fragment(), FragmentCompat.OnRequestPermissionsResultCallback {

    private var backgroundThread: HandlerThread? = null

    // a handler for running in background
    private var backgroundHandler: Handler? = null

    // handles image
    private var imageReader: ImageReader? = null

    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private lateinit var previewRequest: CaptureRequest

    private var captureSession: CameraCaptureSession? = null

    private var cameraDevice: CameraDevice? = null

    // size of camera preview
    private lateinit var previewSize: Size

    // handles when camera device changes state
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            currentCameraDevice.close()
            cameraDevice = null
        }

        override fun onError(currentCameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            currentCameraDevice.close()
            cameraDevice = null

            activity?.let { activity.finish() }
        }

        override fun onOpened(currentCameraDevice: CameraDevice) {
            // camera is opened, start camera preview
            cameraOpenCloseLock.release()
            cameraDevice = currentCameraDevice
            createCameraPreviewSession()
        }
    }

    private lateinit var cameraId: String

    // semaphore to safely close camera before exit
    private val cameraOpenCloseLock = Semaphore(1)

    // a callback for handling capture events
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        }
    }

    private var checkedPermissions: Boolean = false

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {
        }

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
            return true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        startBackgroundThread()
    }

    override fun onResume() {
        super.onResume()

        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureview.isAvailable) {
            openCamera(textureview.width, textureview.height)
        } else {
            textureview.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun openCamera(width: Int, height: Int) {
        if (!checkedPermissions && !allPermissionsGranted()) {
            FragmentCompat.requestPermissions(this, getRequiredPermissions(), PERMISSIONS_REQUEST_CODE)
            return
        } else {
            checkedPermissions = true
        }

        // camera
        setupCameraOutputs(width, height)

        // configure textureview transform
        configureTransform(width, height)

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw RuntimeException("time out lock camera")
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("interrupt lock camera")
        }

    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()

            if (captureSession != null) {
                captureSession?.close()
                captureSession = null
            }

            if (cameraDevice != null) {
                cameraDevice?.close()
                cameraDevice = null
            }

            if (imageReader != null) {
                imageReader?.close()
                imageReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("interrupt lock camera")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /* set up variables related to camera */
    private fun setupCameraOutputs(width: Int, height: Int) {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            for (cameraId in manager.cameraIdList) {
                val cameraCharacteristics = manager.getCameraCharacteristics(cameraId)

                // don't use front camera
                val facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // for still image, use largest size
                val largest = max(map.getOutputSizes(ImageFormat.JPEG).asList(), CompareSizesByArea())
                imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)

                /* set camera preview size */

                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                val rotatedPreviewWidth = width
                val rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH
                }
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT
                }

                previewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth, rotatedPreviewHeight,
                        maxPreviewWidth, maxPreviewHeight, largest)

                // set aspect of texture view to size of preview
                textureview.setAspect(previewSize.height, previewSize.width)

                this.cameraId = cameraId
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            // NPE is thrown when Camera2 is not on device
            ErrorDialog.newInstance("Camera2 error").show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /* configures necessary matrix transformation to textureview */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == textureview || null == activity) {
            return
        }

        val matrix = Matrix()
        val viewRect = RectF(0.0f, 0.0f, viewWidth.toFloat(), viewHeight.toFloat())
        //val bufferRect = RectF(0.0f, 0.0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        matrix.postRotate(180f, centerX, centerY)
        textureview.setTransform(matrix)
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureview.surfaceTexture

            // configure size of default buffer to size of the preview
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            // output screen to start preview
            val surface = Surface(texture)

            // configure output surface
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)

            cameraDevice!!.createCaptureSession(Arrays.asList(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(p0: CameraCaptureSession) {
                    Log.e("debug", "configure error")
                }

                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    captureSession = cameraCaptureSession

                    try {
                        // continuous auto focus
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                        // display camera preview
                        previewRequest = previewRequestBuilder.build()
                        captureSession?.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }
            }, null)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return getRequiredPermissions().none { permission -> permission?.let { ContextCompat.checkSelfPermission(activity, it) } != PackageManager.PERMISSION_GRANTED }
    }

    private fun getRequiredPermissions(): Array<String?> {
        val activity = activity
        return try {
            val info = activity
                    .packageManager
                    .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            val ps = info.requestedPermissions
            if (ps != null && ps.isNotEmpty()) {
                ps
            } else {
                arrayOfNulls(0)
            }
        } catch (e: Exception) {
            arrayOfNulls(0)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(HANDLE_THREAD_NAME)
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }


    /* compares two sizes from area */
    private class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            // cast to ensure multiplication don't overflow
            return java.lang.Long.signum(
                    lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }

    /* resize image to optimal */
    private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough = ArrayList<Size>()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough = ArrayList<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth
                    && option.height <= maxHeight
                    && option.height == option.width * h / w) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size > 0) {
            return Collections.min(bigEnough, CompareSizesByArea())
        } else if (notBigEnough.size > 0) {
            return Collections.max(notBigEnough, CompareSizesByArea())
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size")
            return choices[0]
        }
    }

    /* a class for showing error message dialog */
    class ErrorDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
            return AlertDialog.Builder(activity)
                    .setMessage(arguments.getString(ARG_MESSAGE))
                    .setPositiveButton(
                            android.R.string.ok,
                            { dialogInterface, i -> activity.finish() })
                    .create()
        }

        companion object {

            private val ARG_MESSAGE = "message"

            fun newInstance(message: String): ErrorDialog {
                val dialog = ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): MainFragment {
            return MainFragment()
        }

        val HANDLE_THREAD_NAME = "CameraBackgroundThread"

        val FRAGMENT_DIALOG = "dialog"

        val PERMISSIONS_REQUEST_CODE = 1

        // max width and height by Camera2 API
        val MAX_PREVIEW_WIDTH = 1920
        val MAX_PREVIEW_HEIGHT = 1080
    }

}
