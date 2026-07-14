package com.example.umassistent
import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ComposeView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.firebase.vertexai.VertexAI
import com.google.firebase.vertexai.type.content
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
class UmasumeAIWorker : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    companion object {
        var sharedMediaProjection: MediaProjection? = null
        var screenDensity: Int = 400
        var displayWidth: Int = 1080
        var displayHeight: Int = 2400
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingWindow()
    }
    private fun setupFloatingWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@UmasumeAIWorker)
            setViewTreeSavedStateRegistryOwner(this@UmasumeAIWorker)
            setContent {
                var uiPrompt by remember { mutableStateOf("") }
                var aiResponse by remember { mutableStateOf("Ready for race insights...") }
                var isProcessing by remember { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xE6121212)).padding(16.dp)) {
                    TextField(value = uiPrompt, onValueChange = { uiPrompt = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Ask Gemini about strategy...") })
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Button(enabled = !isProcessing, onClick = {
                            isProcessing = true
                            serviceScope.launch {
                                aiResponse = "Processing screenshot..."
                                val frame = snapScreenFrame()
                                aiResponse = if (frame != null) processPromptWithGemini(uiPrompt, frame) else "Failed to grab display view."
                                isProcessing = false
                            }
                        }) { Text(if (isProcessing) "Analyzing..." else "Snap & Ask") }
                        Button(onClick = { closeAndDismissOverlay() }) { Text("Dismiss") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = aiResponse, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        windowManager.addView(overlayView, params)
    }
    private suspend fun processPromptWithGemini(userMessage: String, screenshot: ByteArray): String {
        val aiCore = VertexAI.type("gemini-2.5-flash")
        val responseFormattingConstraint = " (Reply within 20 words max due to overlay display limits.)"
        val gameMetaContext = " [Context: Umamusume Global Version. Strategy/Primary Focus: Medium distance, Late Surger archetype.] "
        val actionPayloadInstruction = "If an obvious action button choice appears on screen, execute by appending this trailing raw structure format: {\"action\": \"CLICK\", \"x\": X_POS, \"y\": Y_POS}. Do not include markdown code block styling."
        val compoundPrompt = "$userMessage $responseFormattingConstraint $gameMetaContext $actionPayloadInstruction"
        val dynamicContent = content {
            image(screenshot, mimeType = "image/jpeg")
            text(compoundPrompt)
        }
        return withContext(Dispatchers.IO) {
            try {
                val execution = aiCore.generateContent(dynamicContent)
                val textOutput = execution.text ?: "No insight returned."
                if (textOutput.contains("{\"action\": \"CLICK\"")) {
                    parseAndExecuteClick(textOutput)
                    "Executing automated tactical target choice!"
                } else { textOutput }
            } catch (e: Exception) { "Error processing framework: ${e.localizedMessage}" }
        }
    }
    private fun parseAndExecuteClick(aiResponseText: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                val jsonStartIndex = aiResponseText.indexOf("{")
                val jsonEndIndex = aiResponseText.lastIndexOf("}") + 1
                val cleanJson = aiResponseText.substring(jsonStartIndex, jsonEndIndex)
                val payloadObj = JSONObject(cleanJson)
                val targetX = payloadObj.getDouble("x").toFloat()
                val targetY = payloadObj.getDouble("y").toFloat()
                val clickTrajectory = Path().apply { moveTo(targetX, targetY) }
                val eventDescription = GestureDescription.StrokeDescription(clickTrajectory, 0, 60)
                val transactionSequence = GestureDescription.Builder().addStroke(eventDescription).build()
                dispatchGesture(transactionSequence, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        closeAndDismissOverlay()
                    }
                }, null)
            } catch (ex: Exception) { ex.printStackTrace() }
        }
    }
    private fun snapScreenFrame(): ByteArray? {
        val currentProjection = sharedMediaProjection ?: return null
        val imageGrabber = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
        val capturingPlane: VirtualDisplay = currentProjection.createVirtualDisplay("UmasumeViewCapture", displayWidth, displayHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageGrabber.surface, null, null)
        Thread.sleep(150)
        val targetFrame = imageGrabber.acquireLatestImage() ?: return null
        val planeData = targetFrame.planes[0]
        val nativeBuffer = planeData.buffer
        val computedBitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
        computedBitmap.copyPixelsFromBuffer(nativeBuffer)
        targetFrame.close()
        capturingPlane.release()
        imageGrabber.close()
        val outputPipeline = ByteArrayOutputStream()
        computedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputPipeline)
        return outputPipeline.toByteArray()
    }
    private fun closeAndDismissOverlay() {
        overlayView?.let { windowManager.removeView(it); overlayView = null }
        stopSelf()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); serviceScope.cancel() }
}
