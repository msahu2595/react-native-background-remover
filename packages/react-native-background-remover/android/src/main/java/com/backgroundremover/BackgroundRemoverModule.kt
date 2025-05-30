package com.backgroundremover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.pow

class BackgroundRemoverModule internal constructor(context: ReactApplicationContext) :
  BackgroundRemoverSpec(context) {
  private var segmenter: Segmenter? = null

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  override fun removeBackground(imageURI: String, promise: Promise) {
    val segmenter = this.segmenter ?: createSegmenter()
    try {
      val image = getImageBitmap(imageURI)
      val inputImage = InputImage.fromBitmap(image, 0)

      segmenter.process(inputImage).addOnFailureListener { e ->
        promise.reject(e)
      }.addOnSuccessListener { result ->
        val maskBuffer = result.buffer
        maskBuffer.rewind() // Reset buffer position
        
        // Create a new bitmap with transparent background
        val resultBitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
        
        // Get pixel arrays for processing
        val imagePixels = IntArray(result.width * result.height)
        image.getPixels(imagePixels, 0, result.width, 0, 0, result.width, result.height)
        
        val resultPixels = IntArray(result.width * result.height)
        
        // Process each pixel
        for (i in 0 until result.width * result.height) {
          val confidence = maskBuffer.getFloat()
          
          if (confidence > 0.5f) { // Threshold for foreground detection
            // Keep the original pixel with full opacity
            resultPixels[i] = imagePixels[i]
          } else {
            // Make pixel completely transparent
            resultPixels[i] = Color.TRANSPARENT
          }
        }
        
        // Set the processed pixels to result bitmap
        resultBitmap.setPixels(resultPixels, 0, result.width, 0, 0, result.width, result.height)

        val fileName = URI(imageURI).path.split("/").last()
        val savedImageURI = saveImage(resultBitmap, fileName)
        promise.resolve(savedImageURI)
      }
    } catch (e: Exception) {
      promise.reject(e)
    }
  }

  private fun createSegmenter(): Segmenter {
    val options =
      SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()

    val segmenter = Segmentation.getClient(options)
    this.segmenter = segmenter

    return segmenter
  }

  private fun getImageBitmap(imageURI: String): Bitmap {
    val uri = Uri.parse(imageURI)

    return if (uri.scheme == "http" || uri.scheme == "https") {
      val localFile = downloadImage(uri.toString())
      decodeBitmapFromUri(Uri.fromFile(localFile))
    } else {
      decodeBitmapFromUri(uri)
    }
  }

  private fun decodeBitmapFromUri(uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(
          reactApplicationContext.contentResolver,
          uri
        )
      ).copy(Bitmap.Config.ARGB_8888, true)
    } else {
      MediaStore.Images.Media.getBitmap(reactApplicationContext.contentResolver, uri)
    }
  }

  private fun downloadImage(imageUrl: String): File {
    val url = URL(imageUrl)
    val connection = url.openConnection() as HttpURLConnection
    connection.connect()

    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
      throw Exception("Failed to download image: ${connection.responseMessage}")
    }

    val inputStream: InputStream = connection.inputStream
    val tempFile = File.createTempFile("downloaded_image", ".jpg", reactApplicationContext.cacheDir)
    tempFile.outputStream().use { outputStream ->
      inputStream.copyTo(outputStream)
    }
    inputStream.close()
    connection.disconnect()

    return tempFile
  }

  private fun saveImage(bitmap: Bitmap, fileName: String): String {
    val updatedFileName = if (fileName.endsWith(".jpg", ignoreCase = true)) {
      fileName.replace(".jpg", ".png", true)
    } else if (!fileName.endsWith(".png", ignoreCase = true)) {
      "$fileName.png"
    } else {
      fileName
    }
    val file = File(reactApplicationContext.filesDir, updatedFileName)
    val fileOutputStream = FileOutputStream(file)
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
    fileOutputStream.flush()
    fileOutputStream.close()

    return "file://${file.absolutePath}"
  }

  companion object {
    const val NAME = "BackgroundRemover"
  }
}
