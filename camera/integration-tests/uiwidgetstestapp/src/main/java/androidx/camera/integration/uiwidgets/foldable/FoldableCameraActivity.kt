/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.integration.uiwidgets.foldable

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.integration.uiwidgets.databinding.ActivityFoldableCameraBinding
import androidx.camera.integration.uiwidgets.rotations.CameraActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoRepository
import androidx.window.layout.WindowInfoRepository.Companion.windowInfoRepository
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetrics
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FoldableCameraActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "FoldableCameraActivity"
        private const val REQUEST_CODE_PERMISSIONS = 20
        val PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private lateinit var binding: ActivityFoldableCameraBinding
    private lateinit var windowInfoRepository: WindowInfoRepository
    private lateinit var imageCapture: ImageCapture
    private lateinit var camera: Camera
    private lateinit var cameraProvider: ProcessCameraProvider
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var isPreviewInLeftTop = true
    private var activeWindowLayoutInfo: WindowLayoutInfo? = null
    private var lastWindowMetrics: WindowMetrics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFoldableCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        windowInfoRepository = windowInfoRepository()

        if (shouldRequestPermissionsAtRuntime() && !hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasPermissions()) {
                startCamera()
            } else {
                Log.d(TAG, "Camera permission is required")
                finish()
            }
        }
    }

    private fun startCamera() {
        lifecycleScope.launch {
            showCamerasAndDisplayInfo()
            cameraProvider =
                ProcessCameraProvider.getInstance(this@FoldableCameraActivity).await()
            bindUseCases(cameraProvider)
            setupUI()
        }

        // Runs Flow.collect in separate coroutine because it will block the coroutine.
        lifecycleScope.launch {
            windowInfoRepository.currentWindowMetrics.collect {
                Log.d(TAG, "currentWindowMetrics: ${it.bounds}")
                lastWindowMetrics = it
                showCamerasAndDisplayInfo()
            }
        }

        // Runs Flow.collect in separate coroutine because it will block the coroutine.
        lifecycleScope.launch {
            windowInfoRepository.windowLayoutInfo.collect { newLayoutInfo ->
                Log.d(TAG, "newLayoutInfo: $newLayoutInfo")
                activeWindowLayoutInfo = newLayoutInfo
                adjustPreviewByFoldingState()
            }
        }
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .apply {
                setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .build()

        val cameraSelector = CameraSelector.Builder().requireLensFacing(currentLensFacing).build()
        camera = cameraProvider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageCapture
        )
    }

    private fun setupUI() {
        binding.btnTakePicture.setOnClickListener {
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()

            imageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Toast.makeText(
                            this@FoldableCameraActivity,
                            "Image captured successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(
                            this@FoldableCameraActivity, "Failed to capture", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
            }

            cameraProvider.unbindAll()
            bindUseCases(cameraProvider)
        }

        val tapGestureDetector = GestureDetector(this, onTapGestureListener)
        val scaleDetector = ScaleGestureDetector(this, mScaleGestureListener)
        binding.previewView.setOnTouchListener { _, event ->
            val tapEventProcessed = tapGestureDetector.onTouchEvent(event)
            val scaleEventProcessed = scaleDetector.onTouchEvent(event)
            tapEventProcessed || scaleEventProcessed
        }

        binding.btnSwitchArea.setOnClickListener {
            isPreviewInLeftTop = !isPreviewInLeftTop
            adjustPreviewByFoldingState()
        }
    }

    private val mScaleGestureListener: SimpleOnScaleGestureListener =
        object : SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cameraInfo = camera.cameraInfo
                val newZoom =
                    cameraInfo.zoomState.value!!.zoomRatio * detector.scaleFactor
                camera.cameraControl.setZoomRatio(newZoom)
                return true
            }
        }
    private val onTapGestureListener: GestureDetector.OnGestureListener =
        object : SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val factory: MeteringPointFactory = binding.previewView.meteringPointFactory
                val action = FocusMeteringAction.Builder(
                    factory.createPoint(e.x, e.y)
                ).build()

                val future = camera.cameraControl.startFocusAndMetering(action)
                future.addListener({}, { v -> v.run() })
                return true
            }
        }

    private fun adjustPreviewByFoldingState() {
        val previewView = binding.previewView
        val btnSwitchArea = binding.btnSwitchArea
        activeWindowLayoutInfo?.displayFeatures?.firstOrNull { it is FoldingFeature }
            ?.let {
                val rect = getFeaturePositionInViewRect(
                    it,
                    previewView.parent as View
                ) ?: return@let
                val foldingFeature = it as FoldingFeature
                if (foldingFeature.state == FoldingFeature.State.HALF_OPENED) {
                    btnSwitchArea.visibility = View.VISIBLE
                    when (foldingFeature.orientation) {
                        FoldingFeature.Orientation.VERTICAL -> {
                            if (isPreviewInLeftTop) {
                                previewView.moveToLeftOf(rect)
                                val blankAreaWidth =
                                    (btnSwitchArea.parent as View).width - rect.right
                                btnSwitchArea.x = rect.right +
                                    (blankAreaWidth - btnSwitchArea.width) / 2f
                                btnSwitchArea.y = (previewView.height - btnSwitchArea.height) / 2f
                            } else {
                                previewView.moveToRightOf(rect)
                                btnSwitchArea.x =
                                    (rect.left - btnSwitchArea.width) / 2f
                                btnSwitchArea.y = (previewView.height - btnSwitchArea.height) / 2f
                            }
                        }
                        FoldingFeature.Orientation.HORIZONTAL -> {
                            if (isPreviewInLeftTop) {
                                previewView.moveToTopOf(rect)
                                val blankAreaHeight =
                                    (btnSwitchArea.parent as View).height - rect.bottom
                                btnSwitchArea.x = (previewView.width - btnSwitchArea.width) / 2f
                                btnSwitchArea.y = rect.bottom +
                                    (blankAreaHeight - btnSwitchArea.height) / 2f
                            } else {
                                previewView.moveToBottomOf(rect)
                                btnSwitchArea.x = (previewView.width - btnSwitchArea.width) / 2f
                                btnSwitchArea.y =
                                    (rect.top - btnSwitchArea.height) / 2f
                            }
                        }
                    }
                } else {
                    previewView.restore()
                    btnSwitchArea.x = 0f
                    btnSwitchArea.y = 0f
                    btnSwitchArea.visibility = View.INVISIBLE
                }
                showCamerasAndDisplayInfo()
            }
    }

    private fun View.moveToLeftOf(foldingFeatureRect: Rect) {
        x = 0f
        layoutParams = layoutParams.apply {
            width = foldingFeatureRect.left
        }
    }

    private fun View.moveToRightOf(foldingFeatureRect: Rect) {
        x = foldingFeatureRect.left.toFloat()
        layoutParams = layoutParams.apply {
            width = (parent as View).width - foldingFeatureRect.left
        }
    }

    private fun View.moveToTopOf(foldingFeatureRect: Rect) {
        y = 0f
        layoutParams = layoutParams.apply {
            height = foldingFeatureRect.top
        }
    }

    private fun View.moveToBottomOf(foldingFeatureRect: Rect) {
        y = foldingFeatureRect.top.toFloat()
        layoutParams = layoutParams.apply {
            height = (parent as View).height - foldingFeatureRect.top
        }
    }

    private fun View.restore() {
        // Restore to full view
        layoutParams = layoutParams.apply {
            width = MATCH_PARENT
            height = MATCH_PARENT
        }
        y = 0f
        x = 0f
    }

    private fun shouldRequestPermissionsAtRuntime(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    private fun hasPermissions(): Boolean {
        return CameraActivity.PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun getCurrentDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= 30) {
            Api30Compat.getDisplay(this)
        } else {
            windowManager.defaultDisplay
        }
    }

    private val Display.rotationString: String
        get() {
            return when (rotation) {
                Surface.ROTATION_0 -> "0"
                Surface.ROTATION_90 -> "90"
                Surface.ROTATION_180 -> "180"
                Surface.ROTATION_270 -> "270"
                else -> "unknown:$rotation"
            }
        }

    @Suppress("DEPRECATION")
    private fun showCamerasAndDisplayInfo() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val display = getCurrentDisplay()

        val realPt = Point()
        display?.getRealSize(realPt)
        var totalMsg = "Display realSize=$realPt rot=${display?.rotationString} \n" +
            "  WindowMetrics=${lastWindowMetrics?.bounds} \n"

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val msg = "[$id] ${characteristics.lensFacing} " +
                "${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)} degrees \n" +
                "  array = " +
                "${characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} \n" +
                "  focal length = [${characteristics.focalLength}] \n"
            totalMsg += msg
        }

        binding.cameraInfo.text = totalMsg
    }

    private val CameraCharacteristics.lensFacing: String
        get() = when (this.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

    private val CameraCharacteristics.focalLength: String
        get() {
            val focalLengths = this.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (focalLengths == null || focalLengths.isEmpty()) {
                return "NONE"
            }
            return focalLengths.joinToString(",")
        }

    /**
     * Gets the bounds of the display feature translated to the View's coordinate space and current
     * position in the window. This will also include view padding in the calculations.
     *
     * Copied from windowManager Jetpack library sample codes.
     * https://github.com/android/user-interface-samples/tree/main/WindowManager
     *
     */
    fun getFeaturePositionInViewRect(
        displayFeature: DisplayFeature,
        view: View,
        includePadding: Boolean = true
    ): Rect? {
        // The location of the view in window to be in the same coordinate space as the feature.
        val viewLocationInWindow = IntArray(2)
        view.getLocationInWindow(viewLocationInWindow)

        // Intersect the feature rectangle in window with view rectangle to clip the bounds.
        val viewRect = Rect(
            viewLocationInWindow[0], viewLocationInWindow[1],
            viewLocationInWindow[0] + view.width, viewLocationInWindow[1] + view.height
        )

        // Include padding if needed
        if (includePadding) {
            viewRect.left += view.paddingLeft
            viewRect.top += view.paddingTop
            viewRect.right -= view.paddingRight
            viewRect.bottom -= view.paddingBottom
        }

        val featureRectInView = Rect(displayFeature.bounds)
        val intersects = featureRectInView.intersect(viewRect)
        if ((featureRectInView.width() == 0 && featureRectInView.height() == 0) ||
            !intersects
        ) {
            return null
        }

        // Offset the feature coordinates to view coordinate space start point
        featureRectInView.offset(-viewLocationInWindow[0], -viewLocationInWindow[1])

        return featureRectInView
    }
}

@RequiresApi(30)
private object Api30Compat {
    @JvmStatic
    @DoNotInline
    fun getDisplay(activity: Activity): Display? {
        return activity.display
    }
}