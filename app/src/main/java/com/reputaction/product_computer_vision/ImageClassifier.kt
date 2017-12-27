package com.reputaction.product_computer_vision

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.experimental.and


class ImageClassifier @Throws(IOException::class) constructor(activity: Activity) {

    /* Preallocated buffers for storing image data in. */
    private val intValues = IntArray(DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y)

    /* An instance of the driver class to run model inference with Tensorflow Lite. */
    private var tflite: Interpreter?

    /* Labels corresponding to the output of the vision model. */
    private var labelList: List<String>

    /* A ByteBuffer to hold image data, to be feed into Tensorflow Lite as inputs. */
    private var imgData: ByteBuffer?

    /* An array to hold inference results, to be feed into Tensorflow Lite as outputs. */
    private var labelProbArray: Array<ByteArray>

    private val sortedLabels = PriorityQueue(
            RESULTS_TO_SHOW,
            Comparator<Map.Entry<String, Float>> { o1, o2 -> o1.value.compareTo(o2.value) })

    init {
        tflite = Interpreter(loadModelFile(activity))
        labelList = loadLabelList(activity)
        imgData = ByteBuffer.allocateDirect(DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE)
        imgData?.order(ByteOrder.nativeOrder())
        labelProbArray = Array(labelList.size, { ByteArray(1) })
    }

    fun classifyFrame(bitmap: Bitmap): String {
        if (tflite == null) {
            Log.e(TAG, "Image classifier has not been initialized; Skipped.")
            return "Uninitialized Classifier."
        }

        convertBitmapToByteBuffer(bitmap)

        val startTime = SystemClock.uptimeMillis()
        tflite?.run(imgData, labelProbArray)
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to run model inference: " + (endTime - startTime).toString())
        var textToShow = printTopKLabels()
        textToShow = (endTime - startTime).toString() + "ms" + textToShow
        return textToShow
    }

    fun close() {
        tflite?.close()
        tflite = null
    }

    /* Memory-map the model file in Assets. */
    @Throws(IOException::class)
    private fun loadModelFile(activity: Activity): MappedByteBuffer {
        val fileDescriptor = activity.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /* Reads label list from Assets. */
    @Throws(IOException::class)
    private fun loadLabelList(activity: Activity): List<String> {
        val labelList = ArrayList<String>()
        val reader = BufferedReader(InputStreamReader(activity.assets.open(LABEL_PATH)))
        while (true) {
            val line = reader.readLine() ?: break
            labelList.add(line)
        }
        reader.close()
        return labelList
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        if (imgData == null) {
            return
        }

        imgData?.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // convert image to floating point
        var pixel = 0
        val startTime = SystemClock.uptimeMillis()
        for (i in 0 until DIM_IMG_SIZE_X) {
            for (j in 0 until DIM_IMG_SIZE_Y) {
                val `val` = intValues[pixel++]
                imgData?.put((`val` shr 16 and 0xFF).toByte())
                imgData?.put((`val` shr 8 and 0xFF).toByte())
                imgData?.put((`val` and 0xFF).toByte())
            }
        }
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to put values into ByteBuffer: " + java.lang.Long.toString(endTime - startTime))
    }

    private fun printTopKLabels(): String {
        for (i in 0 until labelList.size) {
            sortedLabels.add(AbstractMap.SimpleEntry(labelList.get(i), (labelProbArray[0][i] and 0xff.toByte()) / 255.0f))
            if (sortedLabels.size > RESULTS_TO_SHOW) {
                sortedLabels.poll()
            }
        }

        var textToShow = ""
        val size = sortedLabels.size
        for (i in 0 until size) {
            val label = sortedLabels.poll() as Map.Entry<String, Float>
            textToShow = "\n" + label.key + ":" + label.value + textToShow
        }

        return textToShow
    }

    companion object {
        const val TAG = "reputaction"

        const val MODEL_PATH = "mobilenet_quant_v1_224.tflite"  // name of the model stored in assets
        const val LABEL_PATH = "labels.txt"                     // name of the label file stored in assets

        const val RESULTS_TO_SHOW = 3   // number of classification to show in UI

        /* input dimensions */
        const val DIM_BATCH_SIZE = 1
        const val DIM_PIXEL_SIZE = 3
        const val DIM_IMG_SIZE_X = 224
        const val DIM_IMG_SIZE_Y = 224
    }
}