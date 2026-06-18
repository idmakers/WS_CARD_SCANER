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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class CardScanResult(
    val name: String,
    val serialNumber: String,
    val traits: String? = null,
    val error: String? = null,
    val isFromCache: Boolean = false,
    val isFromLocalOcr: Boolean = false
)

object GeminiCardScanner {
    private const val TAG = "GeminiCardScanner"
    private const val MODEL_NAME = "gemini-3.1-flash-lite"

    private fun findWSSerialInText(text: String): String? {
        val lines = text.split("\n")
        val regex = java.util.regex.Pattern.compile(
            "([A-Za-z0-9]+)/([A-Za-z0-9]+)-([A-Za-z0-9]+)(?:\\s+([A-Za-z]+))?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        )
        for (line in lines) {
            val cell = line.trim()
                .replace("\\", "/")
                .replace("|", "/")
            val matcher = regex.matcher(cell)
            if (matcher.find()) {
                val matched = matcher.group(0) ?: ""
                if (matched.isNotEmpty()) {
                    return matched
                }
            }
        }
        return null
    }

    data class CacheEntry(
        val dHash: Long,
        val result: CardScanResult,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val scanCache = mutableListOf<CacheEntry>()
    private const val MAX_CACHE_SIZE = 10

    private fun calculateDHash(bitmap: Bitmap): Long {
        // Crop the center 70% of the image to focus on card contents and ignore outer camera frame/desk background
        val width = bitmap.width
        val height = bitmap.height
        val cropW = (width * 0.70).toInt().coerceAtLeast(1)
        val cropH = (height * 0.70).toInt().coerceAtLeast(1)
        val startX = (width - cropW) / 2
        val startY = (height - cropH) / 2
        
        val cropped = try {
            Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
        } catch (e: Exception) {
            bitmap
        }

        // Resize to 9x8 to compute a 64-bit gradient hash
        val scaled = Bitmap.createScaledBitmap(cropped, 9, 8, true)
        val pixels = IntArray(72)
        scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        
        // Convert to grayscale
        val gray = IntArray(72)
        for (i in 0 until 72) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
        
        if (scaled != cropped) {
            scaled.recycle()
        }
        if (cropped != bitmap) {
            cropped.recycle()
        }
        
        // 8x8 matrix comparisons (64 bits)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = gray[y * 9 + x]
                val right = gray[y * 9 + x + 1]
                if (left > right) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }
        return hash
    }

    private fun getHammingDistance(h1: Long, h2: Long): Int {
        var dist = 0
        var valDiff = h1 xor h2
        while (valDiff != 0L) {
            dist++
            valDiff = valDiff and (valDiff - 1)
        }
        return dist
    }
    
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

            // Calculate similarity signature hash and check in cache
            val inputHash = calculateDHash(bitmap)
            synchronized(scanCache) {
                for (entry in scanCache) {
                    val distance = getHammingDistance(entry.dHash, inputHash)
                    if (distance <= 16) {
                        Log.d(TAG, "⚡ Cache hit! Highly similar image already scanned (dHash Hamming distance=$distance). Returning result directly: ${entry.result.name} (${entry.result.serialNumber})")
                        return@withContext entry.result.copy(isFromCache = true)
                    }
                }
            }

            // 2. Use free local ML Kit Text Recognition on cache miss
            try {
                Log.d(TAG, "📱 Cache miss. Running local ML Kit Text Recognition...")
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(recognizer.process(inputImage))
                val fullText = visionText.text
                Log.d(TAG, "Local OCR detected text:\n$fullText")
                
                val allBlocks = visionText.textBlocks
                val allLines = allBlocks.flatMap { it.lines }
                
                val regex = java.util.regex.Pattern.compile(
                    "([A-Za-z0-9]+)/([A-Za-z0-9]+)-([A-Za-z0-9]+)(?:\\s+([A-Za-z]+))?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                )
                
                var serialLine: com.google.mlkit.vision.text.Text.Line? = null
                var matchedSerial: String? = null
                
                for (line in allLines) {
                    val cleanLineText = line.text.trim()
                        .replace("\\", "/")
                        .replace("|", "/")
                    val matcher = regex.matcher(cleanLineText)
                    if (matcher.find()) {
                        val matched = matcher.group(0) ?: ""
                        if (matched.isNotEmpty()) {
                            serialLine = line
                            matchedSerial = matched
                            break
                        }
                    }
                }
                
                if (matchedSerial != null && serialLine != null) {
                    Log.d(TAG, "📱 Local OCR Success! WS Serial: $matchedSerial")
                    
                    // 1. Extract card name from the line closest above the serial line
                    var detectedName: String? = null
                    var bestNameLine: com.google.mlkit.vision.text.Text.Line? = null
                    var minDistance = Float.MAX_VALUE
                    
                    val serialBox = serialLine.boundingBox
                    val serialCenterY = serialBox?.let { (it.top + it.bottom) / 2f }
                    
                    for (line in allLines) {
                        val lineText = line.text.trim()
                        if (lineText.isEmpty() || lineText.length < 2) continue
                        if (line == serialLine) continue
                        
                        // Ignore if it matches the serial regex itself
                        val cleanedText = lineText.replace("\\", "/").replace("|", "/")
                        if (regex.matcher(cleanedText).find()) continue
                        
                        // Ignore standard Weiss Schwarz templates/headers/card watermark
                        if (lineText.contains("WEISS", ignoreCase = true) || 
                            lineText.contains("SCHWARZ", ignoreCase = true) ||
                            lineText.contains("GRAPHIC", ignoreCase = true) ||
                            lineText.contains("EMULATOR", ignoreCase = true) ||
                            lineText.contains("DYNAMIC", ignoreCase = true) ||
                            lineText.contains("RENDERER", ignoreCase = true)) continue
                        
                        val lineBox = line.boundingBox
                        if (lineBox != null && serialBox != null && serialCenterY != null) {
                            val lineCenterY = (lineBox.top + lineBox.bottom) / 2f
                            
                            // Must be visually above the serial line
                            if (lineCenterY < serialCenterY) {
                                val dist = serialBox.top - lineBox.bottom
                                if (dist > -15) { // allow slight vertical overlap due to rotation/tilt
                                    if (dist < minDistance) {
                                        minDistance = dist.toFloat()
                                        bestNameLine = line
                                    }
                                }
                            }
                        }
                    }
                    
                    // Sequential fallback if geometric search did not yield a line
                    if (bestNameLine == null) {
                        val serialIndex = allLines.indexOf(serialLine)
                        if (serialIndex > 0) {
                            // Find the non-empty, non-serial line closest before serialLine in the list
                            for (i in (serialIndex - 1) downTo 0) {
                                val candidate = allLines[i]
                                val text = candidate.text.trim()
                                if (text.length >= 2 && 
                                    !text.contains("WEISS", ignoreCase = true) && 
                                    !text.contains("SCHWARZ", ignoreCase = true) && 
                                    !text.contains("GRAPHIC", ignoreCase = true) && 
                                    !text.contains("EMULATOR", ignoreCase = true) &&
                                    !regex.matcher(text.replace("\\", "/").replace("|", "/")).find()
                                ) {
                                    bestNameLine = candidate
                                    break
                                }
                            }
                        }
                    }
                    
                    if (bestNameLine != null) {
                        var rawName = bestNameLine.text.trim()
                        // Clean up any common noise like brackets from mock cards or leading/trailing chars
                        rawName = rawName.removeSurrounding("【", "】")
                            .removeSurrounding("[", "]")
                            .removePrefix("Name:")
                            .removePrefix("名稱:")
                            .trim()
                        if (rawName.isNotEmpty()) {
                            detectedName = rawName
                        }
                        Log.d(TAG, "📱 Local OCR Extracted Card Name: $detectedName")
                    }
                    
                    // 2. Extract traits from lines below the serial line
                    val traitsCandidates = mutableListOf<String>()
                    if (serialBox != null && serialCenterY != null) {
                        for (line in allLines) {
                            if (line == serialLine || line == bestNameLine) continue
                            val lineBox = line.boundingBox
                            if (lineBox != null) {
                                val lineCenterY = (lineBox.top + lineBox.bottom) / 2f
                                if (lineCenterY > serialCenterY) {
                                    val dist = lineBox.top - serialBox.bottom
                                    if (dist > -15 && dist < 120) {
                                        val text = line.text.trim()
                                        if (text.isNotEmpty() && 
                                            text.length >= 2 &&
                                            !text.contains("EMULATOR", ignoreCase = true) && 
                                            !text.contains("RENDERER", ignoreCase = true) &&
                                            !text.contains("DYNAMIC", ignoreCase = true) &&
                                            !regex.matcher(text.replace("\\", "/").replace("|", "/")).find()
                                        ) {
                                            traitsCandidates.add(text)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val detectedTraits = if (traitsCandidates.isNotEmpty()) {
                        traitsCandidates.joinToString(", ")
                            .replace("Traits:", "")
                            .replace("特色:", "")
                            .trim()
                    } else null
                    Log.d(TAG, "📱 Local OCR Extracted Traits: $detectedTraits")
                    
                    // Cross-reference with collection (knownCards) to restore character name and traits for free
                    val matchedKnownCard = knownCards.find {
                        val normSource = it.serialNumber.filter { char -> char.isLetterOrDigit() }.uppercase(java.util.Locale.getDefault())
                        val normTarget = matchedSerial.filter { char -> char.isLetterOrDigit() }.uppercase(java.util.Locale.getDefault())
                        normSource == normTarget
                    }
                    
                    val result = if (matchedKnownCard != null) {
                        CardScanResult(
                            name = matchedKnownCard.name,
                            serialNumber = matchedKnownCard.serialNumber, // Maintain original formatting
                            traits = matchedKnownCard.traits,
                            isFromLocalOcr = true
                        )
                    } else {
                        val setAbbr = matchedSerial.substringBefore("/").uppercase(java.util.Locale.getDefault())
                        CardScanResult(
                            name = detectedName ?: "本地辨識卡牌 ($setAbbr)",
                            serialNumber = matchedSerial,
                            traits = detectedTraits ?: "本地辨識",
                            isFromLocalOcr = true
                        )
                    }
                    
                    // Cache the successful local OCR result
                    synchronized(scanCache) {
                        val alreadyExists = scanCache.any { getHammingDistance(it.dHash, inputHash) <= 16 }
                        if (!alreadyExists) {
                            scanCache.add(0, CacheEntry(inputHash, result.copy(isFromCache = false)))
                            while (scanCache.size > MAX_CACHE_SIZE) {
                                scanCache.removeAt(scanCache.size - 1)
                            }
                        }
                    }
                    
                    return@withContext result
                } else {
                    Log.d(TAG, "📱 Local OCR did not find a valid Weiss Schwarz serial code. Falling through to cloud Gemini API...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "📱 Local OCR processing failed or skipped", e)
            }

            val promptText = """
                You are an elite trading card recognition AI.
                Your task is to scan the high-resolution card image directly to extract and format:
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
                
                Image verification rules:
                - Slashes '/' should not be misread as '1', '|', 'I', 'l', or '`'. Format standard Weiss Schwarz card identifier carefully as "BD/W125".
                - Hyphens '-' must be preserved between the card ID and Card Number.
                - Rarity classes like C, U, R, RR, SR, SP, SEC, OFR, CR, CC must be at the very end and prefixed with a space.
                - Never guess random letters or formats. Match the visual presentation exactly.
                
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
                // Save successful scan result to Cache of up to 10 items max
                val result = successResult!!.copy(isFromCache = false)
                synchronized(scanCache) {
                    val alreadyExists = scanCache.any { getHammingDistance(it.dHash, inputHash) <= 16 }
                    if (!alreadyExists) {
                        scanCache.add(0, CacheEntry(inputHash, result))
                        while (scanCache.size > MAX_CACHE_SIZE) {
                            scanCache.removeAt(scanCache.size - 1)
                        }
                        Log.d(TAG, "Cached successful scan result. Current cache size: ${scanCache.size}")
                    }
                }
                return@withContext result
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
