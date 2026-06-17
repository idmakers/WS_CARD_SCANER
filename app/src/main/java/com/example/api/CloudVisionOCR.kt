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
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

object CloudVisionOCR {
    private const val TAG = "CloudVisionOCR"
    
    // Service Account configuration loaded securely via BuildConfig
    private val clientEmail: String
        get() = BuildConfig.OCR_CLIENT_EMAIL

    private fun getCleanPrivateKeyPem(): String {
        var pem = BuildConfig.OCR_PRIVATE_KEY_PEM
        if (pem.isEmpty() || pem == "YOUR_OCR_PRIVATE_KEY_PEM_HERE") {
            Log.e(TAG, "OCR_PRIVATE_KEY_PEM is default placeholder or empty!")
            return ""
        }
        return pem.replace("\\n", "\n")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            .replace("=", "")
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val cleanKey = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val decoded = Base64.decode(cleanKey, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }

    private fun generateJwtAssertion(): String {
        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }

                val iat = System.currentTimeMillis() / 1000
        val exp = iat + 3600
        val payload = JSONObject().apply {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/cloud-platform")
            put("aud", "https://oauth2.googleapis.com/token")
            put("exp", exp)
            put("iat", iat)
        }

        val headerEncoded = base64UrlEncode(header.toString().toByteArray(Charsets.UTF_8))
        val payloadEncoded = base64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))
        val signatureInput = "$headerEncoded.$payloadEncoded"

        val privateKey = parsePrivateKey(getCleanPrivateKeyPem())
        val signatureInstance = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signatureInput.toByteArray(Charsets.UTF_8))
        }
        val signedBytes = signatureInstance.sign()
        val signatureEncoded = base64UrlEncode(signedBytes)

        return "$signatureInput.$signatureEncoded"
    }

    private suspend fun fetchAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val assertion = generateJwtAssertion()
            val requestBodyString = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$assertion"
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = requestBodyString.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Auth request failed: ${response.code} ${response.body?.string()}")
                    return@withContext null
                }
                val respBodyText = response.body?.string() ?: return@withContext null
                val json = JSONObject(respBodyText)
                return@withContext json.optString("access_token", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching access token", e)
            return@withContext null
        }
    }

    suspend fun performOcr(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val accessToken = fetchAccessToken()
        if (accessToken.isNullOrEmpty()) {
            Log.e(TAG, "Could not acquire OAuth2 access token")
            return@withContext null
        }

        try {
            val base64Image = bitmap.toBase64()

            val feature = JSONObject().apply {
                put("type", "DOCUMENT_TEXT_DETECTION")
            }
            val image = JSONObject().apply {
                put("content", base64Image)
            }
            val requestObj = JSONObject().apply {
                put("image", image)
                put("features", JSONArray().put(feature))
            }
            val rootObj = JSONObject().apply {
                put("requests", JSONArray().put(requestObj))
            }

            val requestBody = rootObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://vision.googleapis.com/v1/images:annotate")
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "OCR Request failed: ${response.code} ${response.body?.string()}")
                    return@withContext null
                }

                val respBodyText = response.body?.string() ?: return@withContext null
                val rootJson = JSONObject(respBodyText)
                val responsesArr = rootJson.optJSONArray("responses")
                if (responsesArr == null || responsesArr.length() == 0) {
                    return@withContext ""
                }

                val responseObj = responsesArr.getJSONObject(0)
                val textAnnotations = responseObj.optJSONArray("textAnnotations")
                if (textAnnotations == null || textAnnotations.length() == 0) {
                    return@withContext ""
                }

                // The first item contains the entire description (the full extracted text)
                return@withContext textAnnotations.getJSONObject(0).optString("description", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Cloud Vision OCR", e)
            return@withContext null
        }
    }
}
