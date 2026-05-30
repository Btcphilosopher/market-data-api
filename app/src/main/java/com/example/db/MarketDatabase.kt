package com.example.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ==========================================
// ROOM ENTITIES
// ==========================================

@Entity(tableName = "market_symbols")
data class MarketSymbolEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val exchange: String,
    val sector: String,
    val marketCap: Long,
    val peRatio: Double,
    val dividendYield: Double,
    val price: Double,
    val change: Double,
    val percentChange: Double,
    val volume: Long,
    val type: String // "equity", "crypto", "index", "etf"
)

@Entity(tableName = "historical_candles")
data class HistoricalCandleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val timeframe: String, // "1m", "5m", "1h", "1d"
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

@Entity(tableName = "financial_news")
data class FinancialNewsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val summary: String,
    val source: String,
    val sentiment: String, // "POSITIVE", "NEUTRAL", "NEGATIVE"
    val relevanceScore: Double, // 0.0 to 1.0
    val timestamp: Long,
    val tickerTag: String // e.g. "AAPL", "market-wide", etc.
)

// ==========================================
// DATA ACCESS OBJECT (DAO)
// ==========================================

@Dao
interface MarketDao {
    @Query("SELECT * FROM market_symbols ORDER BY symbol ASC")
    fun getAllSymbolsFlow(): Flow<List<MarketSymbolEntity>>

    @Query("SELECT * FROM market_symbols ORDER BY symbol ASC")
    suspend fun getAllSymbols(): List<MarketSymbolEntity>

    @Query("SELECT * FROM market_symbols WHERE symbol = :symbol LIMIT 1")
    fun getSymbolFlow(symbol: String): Flow<MarketSymbolEntity?>

    @Query("SELECT * FROM market_symbols WHERE symbol = :symbol LIMIT 1")
    suspend fun getSymbol(symbol: String): MarketSymbolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSymbols(symbols: List<MarketSymbolEntity>)

    @Update
    suspend fun updateSymbol(symbol: MarketSymbolEntity)

    @Query("UPDATE market_symbols SET price = :price, change = :change, percentChange = :percentChange, volume = :volume WHERE symbol = :symbol")
    suspend fun updateSymbolPrice(symbol: String, price: Double, change: Double, percentChange: Double, volume: Long)

    // Historical candles queries
    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp ASC")
    fun getCandlesFlow(symbol: String, timeframe: String): Flow<List<HistoricalCandleEntity>>

    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp ASC")
    suspend fun getCandles(symbol: String, timeframe: String): List<HistoricalCandleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<HistoricalCandleEntity>)

    @Query("DELETE FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun clearCandles(symbol: String, timeframe: String)

    // News queries
    @Query("SELECT * FROM financial_news ORDER BY timestamp DESC")
    fun getAllNewsFlow(): Flow<List<FinancialNewsEntity>>

    @Query("SELECT * FROM financial_news WHERE tickerTag = :symbol OR tickerTag = 'market-wide' ORDER BY timestamp DESC")
    fun getNewsForSymbolFlow(symbol: String): Flow<List<FinancialNewsEntity>>

    @Query("SELECT * FROM financial_news WHERE tickerTag = :symbol OR tickerTag = 'market-wide' ORDER BY timestamp DESC")
    suspend fun getNewsForSymbol(symbol: String): List<FinancialNewsEntity>

    @Query("SELECT * FROM financial_news ORDER BY timestamp DESC")
    suspend fun getAllNews(): List<FinancialNewsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNews(news: List<FinancialNewsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingleNews(news: FinancialNewsEntity)

    @Delete
    suspend fun deleteNews(news: FinancialNewsEntity)
}

// ==========================================
// DATABASE CONTAINER
// ==========================================

@Database(
    entities = [
        MarketSymbolEntity::class,
        HistoricalCandleEntity::class,
        FinancialNewsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MarketDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao

    companion object {
        @Volatile
        private var INSTANCE: MarketDatabase? = null

        fun getDatabase(context: Context): MarketDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MarketDatabase::class.java,
                    "market_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
