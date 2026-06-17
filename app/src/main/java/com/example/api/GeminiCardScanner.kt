package com.example.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class CardScanResult(
    val name: String,
    val serialNumber: String,
    val traits: String? = null,
    val error: String? = null
)

object GeminiCardScanner {
    private const val TAG = "GeminiCardScanner"
    private const val MODEL_NAME = "gemini-3.1-flash-lite"
    
    // Configured with high timeouts for image processing
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Compress as JPEG to keep size reasonable for scanning
        compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun resizeBitmapIfRequired(bitmap: Bitmap, maxDimension: Int = 1600): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }
        val aspectRatio = width.toFloat() / height.toFloat()
        val (newWidth, newHeight) = if (width > height) {
            Pair(maxDimension, (maxDimension / aspectRatio).toInt())
        } else {
            Pair((maxDimension * aspectRatio).toInt(), maxDimension)
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun normalizeSerial(serial: String): String {
        return serial.replace("[/\\\\\\s\\-|]監".toRegex(), "")
            .trim()
            .uppercase(java.util.Locale.getDefault())
    }

    suspend fun scanCardImage(
        rawBitmap: Bitmap,
        knownCards: List<com.example.data.CardEntity> = emptyList(),
        isContinuousMode: Boolean = false
    ): CardScanResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API Key is empty or placeholder!")
            return@withContext CardScanResult(
                name = "",
                serialNumber = "",
                error = "請先在 AI Studio 的 Secrets 面板設定 GEMINI_API_KEY 金鑰。"
            )
        }

        try {
            val bitmap = resizeBitmapIfRequired(rawBitmap, 1600)
            Log.d(TAG, "Attempting Cloud Vision OCR first...")
            val ocrText = CloudVisionOCR.performOcr(bitmap)

            // 1. Local Acceleration: If the recognized OCR text matches a card we already have inside our inventory db,
            // we bypass the Gemini network API entirely!
            val normalizedOcr = normalizeSerial(ocrText ?: "")
            val matchedFromInventory = knownCards.firstOrNull { card ->
                val normKnownSc = normalizeSerial(card.serialNumber)
                normKnownSc.isNotEmpty() && normalizedOcr.contains(normKnownSc)
            }

            if (matchedFromInventory != null) {
                Log.d(TAG, "⚡ Local Cache hit! Found matching card serial: ${matchedFromInventory.serialNumber} inside OCR output.")
                return@withContext CardScanResult(
                    name = matchedFromInventory.name,
                    serialNumber = matchedFromInventory.serialNumber,
                    traits = matchedFromInventory.traits ?: "本地快速快取 (未重合模型)"
                )
            }
            
            val ocrHintsText = if (!ocrText.isNullOrBlank()) {
                "OCR system transcribed the following text suggestions (treat as hints, correct any errors based on the high-resolution image):\n\"\"\"\n$ocrText\n\"\"\""
            } else {
                "No OCR text hints available; rely 100% on the visual image."
            }

            val promptText = """
                You are an elite trading card recognition AI.
                Your task is to scan the card image AND analyze any OCR hints to extract and format:
                1. 名稱 (Name): The character or card title in Japanese or Chinese. E.g. "プロレベルのベーシスト 八幡海鈴" (usually placed inside the main character text label).
                2. 序號 (Serial number / ID): The precise Weiss Schwarz serial.
                3. 特色屬性 (Traits / Attributes): Labeled properties (comma-separated, e.g. "音樂, Ave Mujica").
                
                CRITICAL SERIAL NUMBER SYNTAX RULES:
                Weiss Schwarz serials have an extremely strict format that MUST look like:
                `[Booster Identifier]/[Card Type][Expansion ID]-[Card Number] [Rarity]`
                
                You must visually scan the card bottom-left or bottom border for a small capsule starting with a type indicator like 'CX', 'CH', 'CC', or 'EV'.
                Right next to it is the serial code:
                - Example 1: `BD/W125-024 CR`
                - Example 2: `BD/W125-017 C`
                - Example 3: `BD/W125-005 R`
                - Example 4: `BD/W125-083 U`
                
                OCR Correction rules:
                - Slashes '/' are often misread as '1', '|', 'I', 'l', or '`'. Correct them back to '/' (e.g., if you see "BD1W125" or "BD|W125" or "BDlW125", CORRECT IT to "BD/W125").
                - Hyphens '-' must be preserved between the card ID and Card Number.
                - Rarity classes like C, U, R, RR, SR, SP, SEC, OFR, CR, CC must be at the very end and prefixed with a space.
                - Never guess random letters or formats. Match the visual presentation exactly.
                
                OCR INPUT SCHEME:
                $ocrHintsText
                
                RESPONSE FORM:
                Return raw valid JSON matching the exact schema requirements.
            """.trimIndent()

            // Prepare Gemini Request parts
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", promptText))
            
            // ALWAYS attach image for visual verification and high-fidelity scanning!
            val base64Image = bitmap.toBase64()
            val partImage = JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", base64Image)
            )
            partsArray.put(partImage)

            val contentObj = JSONObject().put("parts", partsArray)
            val contentsArray = JSONArray().put(contentObj)

            // Setup response JSON format schema to guarantee clean structured results
            val responseFormatSchema = JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "The card name found in the red-white region"))
                    .put("serialNumber", JSONObject().put("type", "STRING").put("description", "The main serial code or identifier on the card"))
                    .put("traits", JSONObject().put("type", "STRING").put("description", "Any trait labels or notes found in the yellow-black region"))
                )
                .put("required", JSONArray().put("name").put("serialNumber"))

            val generationConfig = JSONObject()
                .put("temperature", 0.1) // Low temperature for high accuracy OCR rules
                .put("responseMimeType", "application/json")
                .put("responseSchema", responseFormatSchema)

            val requestJson = JSONObject()
                .put("contents", contentsArray)
                .put("generationConfig", generationConfig)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)
            
            val candidateModels = listOf(
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash-lite",
                "gemini-2.5-flash",
                "gemini-3.5-flash",
                "gemini-3.1-pro-preview"
            )
            
            var lastErrorMsg = ""
            var successResult: CardScanResult? = null
            
            for (model in candidateModels) {
                Log.d(TAG, "Trying model: $model")
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBodyText = response.body?.string()
                            if (!responseBodyText.isNullOrEmpty()) {
                                val outerObj = JSONObject(responseBodyText)
                                val candidatesArr = outerObj.optJSONArray("candidates")
                                if (candidatesArr != null && candidatesArr.length() > 0) {
                                    val content = candidatesArr.getJSONObject(0).optJSONObject("content")
                                    val parts = content?.optJSONArray("parts")
                                    if (parts != null && parts.length() > 0) {
                                        val extractedJsonText = parts.getJSONObject(0).optString("text")
                                        if (!extractedJsonText.isNullOrEmpty()) {
                                            Log.d(TAG, "Successfully scanned with model $model. Raw extracted JSON: $extractedJsonText")
                                            val resultObj = JSONObject(extractedJsonText)
                                            val name = resultObj.optString("name", "").trim()
                                            val serialNumber = resultObj.optString("serialNumber", "").trim()
                                            val traits = resultObj.optString("traits", "").trim()
                                            successResult = CardScanResult(
                                                name = name.ifEmpty { "未命名卡片" },
                                                serialNumber = serialNumber.ifEmpty { "未知序號" },
                                                traits = traits.ifEmpty { null }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val errBody = response.body?.string() ?: ""
                            lastErrorMsg = "模型 $model 失敗: HTTP ${response.code} - $errBody"
                            Log.e(TAG, "Request failed for model $model: code=${response.code} body=$errBody")
                        }
                    }
                } catch (e: Exception) {
                    lastErrorMsg = "模型 $model 異常: ${e.localizedMessage ?: e.message}"
                    Log.e(TAG, "Exception with model $model", e)
                }
                
                if (successResult != null) {
                    break
                }
            }
            
            if (successResult != null) {
                return@withContext successResult!!
            } else {
                return@withContext CardScanResult(
                    name = "",
                    serialNumber = "",
                    error = "所有模型皆無法辨識。\n最後錯誤內容：$lastErrorMsg"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during scanning: ", e)
            return@withContext CardScanResult(
                name = "",
                serialNumber = "",
                error = "發生錯誤: ${e.localizedMessage ?: e.message}"
            )
        }
    }
}
