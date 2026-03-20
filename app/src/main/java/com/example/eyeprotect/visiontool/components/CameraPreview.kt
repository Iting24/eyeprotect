package com.example.eyeprotect.visiontool.components

import android.util.Log
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

@OptIn(TransformExperimental::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
    outputImageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
    onPreviewViewReady: (PreviewView) -> Unit = {},
    onMaskTransform: (android.graphics.Matrix, Int, Int) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // PreviewView outputTransform must be read on main thread only.
    val outputTransformRef = remember { AtomicReference<OutputTransform?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            onPreviewViewReady(previewView)

            val executor = ContextCompat.getMainExecutor(ctx)
            val transformFactory = ImageProxyTransformFactory().apply {
                // Avoid double-rotation; let CoordinateTransform handle orientation.
                isUsingRotationDegrees = false
            }

            // Update outputTransform on main thread when layout changes.
            previewView.post { outputTransformRef.set(previewView.outputTransform) }
            previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                outputTransformRef.set(previewView.outputTransform)
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                previewView.post {
                    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    val viewW = previewView.width
                    val viewH = previewView.height

                    val previewBuilder = Preview.Builder()
                        .setTargetRotation(rotation)

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(outputImageFormat)
                        // Keep analysis in sensor buffer orientation; transform will rotate.
                        .setTargetRotation(Surface.ROTATION_0)

                    if (viewW > 0 && viewH > 0) {
                        val target = android.util.Size(viewW, viewH)
                        previewBuilder.setTargetResolution(target)
                        analysisBuilder.setTargetResolution(target)
                    } else {
                        previewBuilder.setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        analysisBuilder.setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    }

                    val preview = previewBuilder.build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val wrappedAnalyzer = analyzer?.let { base ->
                        ImageAnalysis.Analyzer { image ->
                            val previewTransform = outputTransformRef.get()
                            if (previewTransform != null) {
                                val imageTransform = transformFactory.getOutputTransform(image)
                                val coordinateTransform = CoordinateTransform(imageTransform, previewTransform)

                                val src = floatArrayOf(
                                    0f, 0f,
                                    image.width.toFloat(), 0f,
                                    image.width.toFloat(), image.height.toFloat(),
                                    0f, image.height.toFloat()
                                )
                                val dst = src.clone()
                                coordinateTransform.mapPoints(dst)

                                val matrix = android.graphics.Matrix()
                                matrix.setPolyToPoly(src, 0, dst, 0, 4)
                                onMaskTransform(matrix, image.width, image.height)
                            }

                            base.analyze(image)
                        }
                    }

                    val imageAnalysis = wrappedAnalyzer?.let { nonNullAnalyzer ->
                        analysisBuilder.build().also { it.setAnalyzer(analysisExecutor, nonNullAnalyzer) }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            *listOfNotNull(imageAnalysis).toTypedArray()
                        )
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "Camera bind failed", e)
                    }
                }
            }, executor)

            previewView
        },
        modifier = modifier
    )
}
