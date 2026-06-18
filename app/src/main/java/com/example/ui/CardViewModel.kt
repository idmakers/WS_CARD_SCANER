package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiCardScanner
import com.example.data.AppDatabase
import com.example.data.CardEntity
import com.example.data.CardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.mutableStateListOf

data class BackgroundScanTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val bitmap: Bitmap,
    val namePlaceholder: String = "連拍進度 #${(System.currentTimeMillis() % 1000).toInt()}",
    val status: String = "AI 辨識處理中...",
    val isSuccess: Boolean? = null // null = processing, true = success, false = failure
)

class CardViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = CardRepository(database.cardDao)

    init {
        // Clear all temporary images inside cacheDir from previous runs
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = application.cacheDir
                val tempFiles = cacheDir.listFiles { _, name ->
                    name.startsWith("camera_raw_") || name.startsWith("cam_manual_")
                }
                tempFiles?.forEach { file ->
                    if (file.exists()) {
                        file.delete()
                        Log.d("CardViewModel", "Pruned stale temp cache file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("CardViewModel", "Failed to clean old cache files on start", e)
            }
        }
    }

    // SharedPreferences for persistent billing info
    private val prefs = application.getSharedPreferences("billing_token_prefs", Context.MODE_PRIVATE)

    var remainingScans by mutableStateOf(prefs.getInt("remaining_scans", 50)) // Default 50 free credits
        private set

    var subscriptionLevel by mutableStateOf(prefs.getInt("subscription_level", 0)) // 0: None, 1: Tier 1, 2: Tier 2
        private set

    var purchasedTokenCount by mutableStateOf(prefs.getInt("purchased_token_count", 0))
        private set

    var userEmail by mutableStateOf(prefs.getString("user_email", "") ?: "")
        private set

    val isTester: Boolean
        get() = userEmail.trim().lowercase() == "idmakers@gmail.com"

    fun updateUserEmail(newEmail: String) {
        userEmail = newEmail
        prefs.edit().putString("user_email", newEmail).apply()
    }

    private fun saveBillingState() {
        prefs.edit().apply {
            putInt("remaining_scans", remainingScans)
            putInt("subscription_level", subscriptionLevel)
            putInt("purchased_token_count", purchasedTokenCount)
            apply()
        }
    }

    // Purchase token package: NT$30 -> 100 scans
    fun buyTokenPackage() {
        purchasedTokenCount += 1
        remainingScans += 100
        saveBillingState()
    }

    // Subscribe Tier 1: NT$99/month -> 1,000 scans
    fun subscribeTier1() {
        subscriptionLevel = 1
        remainingScans += 1000
        saveBillingState()
    }

    // Subscribe Tier 2: NT$199/month -> 10,000 scans
    fun subscribeTier2() {
        subscriptionLevel = 2
        remainingScans += 10000
        saveBillingState()
    }

    // Cancel / Reset state to initial status
    fun cancelSubscriptionAndReset() {
        subscriptionLevel = 0
        purchasedTokenCount = 0
        remainingScans = 50 // Reset count
        saveBillingState()
    }

    // Continuous/Batch mode status (Pro unlocked feature)
    var isContinuousMode by mutableStateOf(false)
        private set

    val activeBackgroundScans = mutableStateListOf<BackgroundScanTask>()

    fun toggleContinuousMode() {
        isContinuousMode = !isContinuousMode
    }

    fun removeScanTask(task: BackgroundScanTask) {
        activeBackgroundScans.remove(task)
    }

    fun clearScanTasks() {
        activeBackgroundScans.clear()
    }

    // Search and filter parameters
    var searchQuery by mutableStateOf("")
        private set
    
    var sortOrder by mutableStateOf("date_desc") // date_desc, date_asc, qty_desc, name, serial
        private set

    // Scanner UI states
    var isScanning by mutableStateOf(false)
        private set

    var scanError by mutableStateOf<String?>(null)
        private set

    var lastScannedCard by mutableStateOf<CardEntity?>(null)
        private set

    var lastScanIsFromCache by mutableStateOf(false)
        private set

    var lastScanIsFromLocalOcr by mutableStateOf(false)
        private set

    // Combined Flow for UI list reactively matching search and sorting
    val cardsState: StateFlow<List<CardEntity>> = combine(
        repository.allCards,
        MutableStateFlow(Unit) // dummy state trigger to refresh lists
    ) { originalList, _ ->
        // Apply text queries
        val filtered = if (searchQuery.isBlank()) {
            originalList
        } else {
            val queryLower = searchQuery.lowercase(Locale.getDefault())
            originalList.filter { card ->
                card.name.lowercase(Locale.getDefault()).contains(queryLower) ||
                card.serialNumber.lowercase(Locale.getDefault()).contains(queryLower) ||
                (card.traits ?: "").lowercase(Locale.getDefault()).contains(queryLower)
            }
        }

        // Apply sorting criteria
        when (sortOrder) {
            "date_desc" -> filtered.sortedByDescending { card -> card.scannedDate }
            "date_asc" -> filtered.sortedBy { card -> card.scannedDate }
            "qty_desc" -> filtered.sortedByDescending { card -> card.quantity }
            "name" -> filtered.sortedBy { card -> card.name }
            "serial" -> filtered.sortedBy { card -> card.serialNumber }
            else -> filtered
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun onSearchQueryChanged(newQuery: String) {
        searchQuery = newQuery
        triggerUpdate()
    }

    fun onSortOrderChanged(newSort: String) {
        sortOrder = newSort
        triggerUpdate()
    }

    // Force recomposition on Flow combines
    private fun triggerUpdate() {
        // Simple trick to force combined flows to re-evaluate when standard states change
        viewModelScope.launch {
            // Room Flow is reactive, so we don't necessarily have to refresh, but combine needs a trigger
        }
    }

    fun dismissScanStates() {
        scanError = null
        lastScannedCard = null
    }

    fun scanCardImageContinuous(bitmap: Bitmap) {
        if (!isTester && remainingScans <= 0) {
            val task = BackgroundScanTask(
                bitmap = bitmap,
                status = "額度不足！請於下方選購代幣或訂閱會員方案",
                isSuccess = false
            )
            activeBackgroundScans.add(0, task)
            viewModelScope.launch {
                kotlinx.coroutines.delay(10000)
                activeBackgroundScans.removeAll { it.id == task.id }
            }
            return
        }

        // Deduct 1 credit for continuous scan and save (Skip if tester)
        if (!isTester) {
            remainingScans--
            saveBillingState()
        }

        val task = BackgroundScanTask(bitmap = bitmap)
        activeBackgroundScans.add(0, task) // Add to top of queue
        val known = cardsState.value
        
        viewModelScope.launch {
            try {
                val response = GeminiCardScanner.scanCardImage(bitmap, known, isContinuousMode = true)
                val updatedIndex = activeBackgroundScans.indexOfFirst { it.id == task.id }
                if (response.error != null) {
                    if (updatedIndex != -1) {
                        activeBackgroundScans[updatedIndex] = activeBackgroundScans[updatedIndex].copy(
                            status = "已跳過: ${response.error}",
                            isSuccess = false
                        )
                    }
                } else {
                    // Success! Insert or increment card data
                    val scannedCard = insertOrIncrementCard(
                        name = response.name,
                        serialNumber = response.serialNumber,
                        traits = response.traits
                    )
                    
                    // Refund credit since it was served for free from cache or local ML Kit!
                    if (!isTester && (response.isFromCache || response.isFromLocalOcr)) {
                        remainingScans = (remainingScans + 1).coerceAtMost(99999)
                        saveBillingState()
                    }
                    
                    if (updatedIndex != -1) {
                        val sourceLabel = if (response.isFromCache) {
                            "⚡ [快取]"
                        } else if (response.isFromLocalOcr) {
                            "📱 [本地]"
                        } else {
                            "🌐 [API]"
                        }
                        activeBackgroundScans[updatedIndex] = activeBackgroundScans[updatedIndex].copy(
                            status = "$sourceLabel 成功: ${scannedCard.name} (${scannedCard.serialNumber})",
                            isSuccess = true
                        )
                    }
                }
            } catch (e: Exception) {
                val updatedIndex = activeBackgroundScans.indexOfFirst { it.id == task.id }
                if (updatedIndex != -1) {
                    activeBackgroundScans[updatedIndex] = activeBackgroundScans[updatedIndex].copy(
                        status = "失敗: ${e.localizedMessage ?: e.message}",
                        isSuccess = false
                    )
                }
            } finally {
                // Keep completed visual states briefly then let them disappear
                viewModelScope.launch {
                    kotlinx.coroutines.delay(10000) // Keep in list for 10 seconds, then remove
                    activeBackgroundScans.removeAll { it.id == task.id }
                }
            }
        }
    }

    // Process of scanning image via Gemini API with colors recognition rule
    fun scanCardImage(bitmap: Bitmap, forceContinuous: Boolean = false) {
        if (isContinuousMode || forceContinuous) {
            scanCardImageContinuous(bitmap)
            return
        }
        if (!isTester && remainingScans <= 0) {
            scanError = "您的剩餘掃描次數不足！請於下方選購代幣或訂閱會員方案"
            return
        }

        // Deduct 1 credit for scan and save (Skip if tester)
        if (!isTester) {
            remainingScans--
            saveBillingState()
        }

        val known = cardsState.value
        viewModelScope.launch {
            isScanning = true
            scanError = null
            lastScannedCard = null
            lastScanIsFromCache = false
            lastScanIsFromLocalOcr = false
            
            try {
                val response = GeminiCardScanner.scanCardImage(bitmap, known, isContinuousMode = false)
                
                if (response.error != null) {
                    scanError = response.error
                    lastScanIsFromCache = false
                    lastScanIsFromLocalOcr = false
                } else {
                    lastScanIsFromCache = response.isFromCache
                    lastScanIsFromLocalOcr = response.isFromLocalOcr
                    
                    // Refund credit since it was served for free from cache or local ML Kit!
                    if (!isTester && (response.isFromCache || response.isFromLocalOcr)) {
                        remainingScans = (remainingScans + 1).coerceAtMost(99999)
                        saveBillingState()
                    }
                    
                    // Success! Insert or increment card data
                    val scannedCard = insertOrIncrementCard(
                        name = response.name,
                        serialNumber = response.serialNumber,
                        traits = response.traits
                    )
                    lastScannedCard = scannedCard
                }
            } catch (e: Exception) {
                scanError = "辨識過程中發生異常: ${e.localizedMessage ?: e.message}"
            } finally {
                isScanning = false
            }
        }
    }

    // Insert new card, or increment count if card exists with matched name and serial properties
    private suspend fun insertOrIncrementCard(
        name: String,
        serialNumber: String,
        traits: String?
    ): CardEntity = withContext(Dispatchers.IO) {
        // Collect current list to find match
        val currentList = database.cardDao.getAllCards()
        // Wait, room returns flow so we can query database directly or fetch first
        // Let's query matching item
        val match = queryMatchedCard(name, serialNumber)
        
        val targetCard = if (match != null) {
            val updated = match.copy(
                quantity = match.quantity + 1,
                scannedDate = System.currentTimeMillis(), // update last scan date
                traits = traits ?: match.traits // preserve or enrich traits
            )
            repository.update(updated)
            updated
        } else {
            val fresh = CardEntity(
                name = name,
                serialNumber = serialNumber,
                quantity = 1,
                scannedDate = System.currentTimeMillis(),
                traits = traits
            )
            repository.insert(fresh)
            fresh
        }
        targetCard
    }

    private fun normalizeSerialNumberForComparison(serial: String): String {
        return serial.filter { it.isLetterOrDigit() }.uppercase(java.util.Locale.getDefault())
    }

    private fun queryMatchedCard(name: String, serialNumber: String): CardEntity? {
        var matchedItem: CardEntity? = null
        try {
            val elements = cardsState.value
            val normInput = normalizeSerialNumberForComparison(serialNumber)
            
            if (normInput.isNotEmpty()) {
                matchedItem = elements.find { card ->
                    val normCard = normalizeSerialNumberForComparison(card.serialNumber)
                    normCard.isNotEmpty() && normCard == normInput
                }
            }
            
            // Fallback: match by name ignoring uppercase/lowercase if serial is empty
            if (matchedItem == null && name.trim().isNotEmpty()) {
                matchedItem = elements.find { card ->
                    card.name.trim().equals(name.trim(), ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.e("CardViewModel", "Error finding matching card", e)
        }
        return matchedItem
    }

    // Increment count
    fun incrementCardQuantity(card: CardEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = card.copy(quantity = card.quantity + 1)
            repository.update(updated)
        }
    }

    // Decrement count (safely drops to 1, or can delete if user hits zero - let's set minimum to 1)
    fun decrementCardQuantity(card: CardEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (card.quantity > 1) {
                val updated = card.copy(quantity = card.quantity - 1)
                repository.update(updated)
            } else {
                repository.delete(card)
            }
        }
    }

    // Delete item
    fun deleteCard(card: CardEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(card)
        }
    }

    // Clear whole database
    fun clearInventory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAll()
        }
    }

    // Export to CSV Excel structure
    fun exportToExcel(context: Context, onCompleted: (Uri?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cards = cardsState.value
                val dateStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "卡牌清冊統計_$dateStamp.csv"
                
                // Save to app cache directory so we can share via FileProvider safely
                val cacheDir = context.cacheDir
                val file = File(cacheDir, fileName)
                
                file.outputStream().use { outputStream ->
                    // Write UTF-8 BOM to prevent Excel garbled text (Excel needs EF BB BF for UTF-8)
                    outputStream.write(0xEF)
                    outputStream.write(0xBB)
                    outputStream.write(0xBF)
                    
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write("序號 (Index),卡牌名稱 (Card Name - 紅底白字),卡片序號 (Serial ID - 黃底黑字),統計數量 (Quantity),特色標籤 (Traits),最近掃描時間 (Scan Date)\n")
                        
                        cards.forEachIndexed { index, card ->
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                .format(Date(card.scannedDate))
                            
                            // Prevent column breaks by cleaning CSV quotes
                            val escapedName = card.name.replace("\"", "\"\"")
                            val escapedSerial = card.serialNumber.replace("\"", "\"\"")
                            val escapedTraits = (card.traits ?: "").replace("\"", "\"\"")
                            
                            writer.write("${index + 1},\"$escapedName\",\"$escapedSerial\",${card.quantity},\"$escapedTraits\",\"$dateStr\"\n")
                        }
                        writer.flush()
                    }
                }
                
                // Create FileProvider Uri for sharing
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                withContext(Dispatchers.Main) {
                    onCompleted(uri)
                }
            } catch (e: Exception) {
                Log.e("CardViewModel", "Export failed: ", e)
                withContext(Dispatchers.Main) {
                    onCompleted(null)
                }
            }
        }
    }

    // Share action launcher Helper
    fun triggerShareIntent(context: Context, fileUri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "卡牌匯出清單")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "選擇分享或匯出至試算表 (Excel)"))
    }
}
