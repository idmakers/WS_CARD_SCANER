package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val serialNumber: String,
    val quantity: Int = 1,
    val scannedDate: Long = System.currentTimeMillis(),
    val imageUrl: String? = null,
    val traits: String? = null
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY scannedDate DESC")
    fun getAllCards(): Flow<List<CardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardEntity)

    @Update
    suspend fun updateCard(card: CardEntity)

    @Delete
    suspend fun deleteCard(card: CardEntity)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCardById(id: Int)

    @Query("DELETE FROM cards")
    suspend fun deleteAllCards()
}

@Database(entities = [CardEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract val cardDao: CardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "card_inventory_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class CardRepository(private val cardDao: CardDao) {
    val allCards: Flow<List<CardEntity>> = cardDao.getAllCards()

    suspend fun insert(card: CardEntity) = cardDao.insertCard(card)

    suspend fun update(card: CardEntity) = cardDao.updateCard(card)

    suspend fun delete(card: CardEntity) = cardDao.deleteCard(card)

    suspend fun deleteById(id: Int) = cardDao.deleteCardById(id)

    suspend fun deleteAll() = cardDao.deleteAllCards()
}
