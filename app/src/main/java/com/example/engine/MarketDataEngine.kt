package com.example.engine

import android.content.Context
import android.util.Log
import com.example.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// Connection model for logging API requests on the visual dashboard
data class ApiRequestLog(
    val timestamp: Long = System.currentTimeMillis(),
    val clientIp: String,
    val method: String,
    val path: String,
    val status: Int,
    val durationMs: Long
)

class MarketDataEngine(private val context: Context) {
    private val scope = CoroutineScope(DispatchScopeProvider.dispatcher + SupervisorJob())
    private val database = MarketDatabase.getDatabase(context)
    private val dao = database.marketDao()

    // Fast in-memory hot cache for live ticket values
    private val symbolCache = ConcurrentHashMap<String, MarketSymbolEntity>()

    // Live Streams
    private val _liveTickFlow = MutableSharedFlow<MarketSymbolEntity>(replay = 0, extraBufferCapacity = 64)
    val liveTickFlow: SharedFlow<MarketSymbolEntity> = _liveTickFlow

    private val _connectionLogs = MutableStateFlow<List<ApiRequestLog>>(emptyList())
    val connectionLogs: StateFlow<List<ApiRequestLog>> = _connectionLogs

    private val _activeWebSocketCount = MutableStateFlow(0)
    val activeWebSocketCount: StateFlow<Int> = _activeWebSocketCount

    private val _updateFrequencyMs = MutableStateFlow(500L) // Customizable update speed (200ms - 2000ms)
    val updateFrequencyMs: StateFlow<Long> = _updateFrequencyMs

    private var simulationJob: Job? = null

    init {
        scope.launch {
            initDatabaseIfEmpty()
            loadSymbolsIntoCache()
            startSimulation()
        }
    }

    private suspend fun loadSymbolsIntoCache() {
        val symbols = dao.getAllSymbols()
        symbols.forEach {
            symbolCache[it.symbol] = it
        }
    }

    fun setUpdateFrequency(ms: Long) {
        _updateFrequencyMs.value = ms.coerceIn(200L, 5000L)
        startSimulation() // Restart simulation with new interval
    }

    fun logConnection(clientIp: String, method: String, path: String, status: Int, durationMs: Long) {
        val log = ApiRequestLog(
            clientIp = clientIp,
            method = method,
            path = path,
            status = status,
            durationMs = durationMs
        )
        val current = _connectionLogs.value.toMutableList()
        current.add(0, log)
        if (current.size > 50) current.removeAt(current.size - 1)
        _connectionLogs.value = current
    }

    fun incrementWebSockets() {
        _activeWebSocketCount.value += 1
    }

    fun decrementWebSockets() {
        _activeWebSocketCount.value = (_activeWebSocketCount.value - 1).coerceAtLeast(0)
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            while (isActive) {
                delay(_updateFrequencyMs.value)
                simulateTick()
            }
        }
    }

    private suspend fun simulateTick() {
        if (symbolCache.isEmpty()) return
        
        // Pick 1-3 random symbols to tick
        val tickerCount = Random.nextInt(1, 4)
        val keys = symbolCache.keys.toList()
        
        repeat(tickerCount) {
            val randomKey = keys[Random.nextInt(keys.size)]
            val oldEntity = symbolCache[randomKey] ?: return@repeat

            // Calculate Brownian Motion walk
            // Volatility parameter varies by asset type
            val vol = when (oldEntity.type) {
                "crypto" -> 0.0055 // High vol
                "equity" -> 0.0018 // Med-low
                "etf" -> 0.0008    // Low vol
                else -> 0.0006     // Market Index
            }
            
            val changePercent = (Random.nextDouble() - 0.49) * vol // Subtle upward bias
            val oldPrice = oldEntity.price
            val newPrice = (oldPrice * (1.0 + changePercent)).coerceAtLeast(0.01)
            val diff = newPrice - oldPrice
            
            val oldPercent = oldEntity.percentChange
            val theoreticalDayStart = oldPrice / (1.0 + oldPercent / 100.0)
            val newPercentChange = ((newPrice - theoreticalDayStart) / theoreticalDayStart) * 100.0
            val newChange = newPrice - theoreticalDayStart
            
            val extraVolume = Random.nextLong(100, 2500)
            val newVolume = oldEntity.volume + extraVolume

            val newEntity = oldEntity.copy(
                price = (Math.round(newPrice * 100.0) / 100.0),
                change = (Math.round(newChange * 100.0) / 100.0),
                percentChange = (Math.round(newPercentChange * 100.0) / 100.0),
                volume = newVolume
            )

            // Update in cache
            symbolCache[randomKey] = newEntity

            // Emit tick flow for WebSockets
            _liveTickFlow.emit(newEntity)

            // Periodically persist to DB in batches, or update symbol
            dao.updateSymbolPrice(
                symbol = newEntity.symbol,
                price = newEntity.price,
                change = newEntity.change,
                percentChange = newEntity.percentChange,
                volume = newEntity.volume
            )

            // Also check if we should update or append to the latest 1-min candle
            updateLatestCandle(newEntity)
        }
    }

    private suspend fun updateLatestCandle(symbol: MarketSymbolEntity) {
        val timeframes = listOf("1m", "5m")
        timeframes.forEach { tf ->
            val candles = dao.getCandles(symbol.symbol, tf)
            val nowMs = System.currentTimeMillis()
            val intervalMs = if (tf == "1m") 60_000L else 300_000L
            val currentBucketUnix = (nowMs / intervalMs) * intervalMs

            if (candles.isNotEmpty() && candles.last().timestamp == currentBucketUnix) {
                val lastCandle = candles.last()
                val updated = lastCandle.copy(
                    high = maxOf(lastCandle.high, symbol.price),
                    low = minOf(lastCandle.low, symbol.price),
                    close = symbol.price,
                    volume = lastCandle.volume + Random.nextInt(10, 100)
                )
                dao.insertCandles(listOf(updated))
            } else {
                val newCandle = HistoricalCandleEntity(
                    symbol = symbol.symbol,
                    timeframe = tf,
                    timestamp = currentBucketUnix,
                    open = symbol.price,
                    high = symbol.price,
                    low = symbol.price,
                    close = symbol.price,
                    volume = Random.nextLong(500, 2500)
                )
                dao.insertCandles(listOf(newCandle))
                
                if (candles.size > 100) {
                    dao.clearCandles(symbol.symbol, tf)
                    dao.insertCandles(candles.takeLast(80))
                }
            }
        }
    }

    private suspend fun initDatabaseIfEmpty() {
        val existingSymbols = dao.getAllSymbols()
        if (existingSymbols.isNotEmpty()) return

        Log.d("MarketDataEngine", "Seeding default market symbol, historical, and news databases...")

        val defaultSymbols = listOf(
            MarketSymbolEntity("AAPL", "Apple Inc.", "NASDAQ", "Technology", 2950000000000L, 28.5, 0.55, 175.45, 1.25, 0.72, 52000000L, "equity"),
            MarketSymbolEntity("MSFT", "Microsoft Corp.", "NASDAQ", "Technology", 3120000000000L, 35.2, 0.72, 415.50, -2.40, -0.57, 24000000L, "equity"),
            MarketSymbolEntity("TSLA", "Tesla Inc.", "NASDAQ", "Automotive", 580000000000L, 48.1, 0.0, 182.10, 4.85, 2.74, 88000000L, "equity"),
            MarketSymbolEntity("NVDA", "NVIDIA Corporation", "NASDAQ", "Technology", 2200000000000L, 72.4, 0.04, 875.12, 18.40, 2.15, 41000000L, "equity"),
            MarketSymbolEntity("AMZN", "Amazon.com Inc.", "NASDAQ", "Consumer Cyclical", 1850000000000L, 42.1, 0.0, 178.15, -0.85, -0.48, 31000000L, "equity"),
            MarketSymbolEntity("SPY", "SPDR S&P 500 ETF Trust", "NYSE Arca", "Market Tracker", 500000000000L, 24.1, 1.32, 512.40, 1.15, 0.22, 75000000L, "etf"),
            MarketSymbolEntity("QQQ", "Invesco QQQ Trust", "NASDAQ", "Tech Growth Tracker", 220000000000L, 31.5, 0.58, 438.25, 1.85, 0.42, 48000000L, "etf"),
            MarketSymbolEntity("BTC", "Bitcoin USD", "Decentralized", "Cryptocurrency", 1320000000000L, 0.0, 0.0, 67450.00, 1250.00, 1.89, 28000000000L, "crypto"),
            MarketSymbolEntity("ETH", "Ethereum USD", "Decentralized", "Cryptocurrency", 410000000000L, 0.0, 0.0, 3480.00, -45.00, -1.28, 14000000000L, "crypto"),
            MarketSymbolEntity(".INX", "S&P 500 Index", "INDEX", "Macro Indicator", 44000000000000L, 25.4, 1.45, 5220.50, 14.20, 0.27, 2400000000L, "index"),
            MarketSymbolEntity(".IXIC", "NASDAQ Composite", "INDEX", "Macro Indicator", 26000000000000L, 34.2, 0.82, 16270.20, 85.40, 0.53, 3800000000L, "index"),
            MarketSymbolEntity(".FTSE", "FTSE 100 Index", "LSE", "Macro Indicator", 2500000000000L, 15.1, 3.85, 8250.60, -22.40, -0.27, 850000000L, "index")
        )

        dao.insertSymbols(defaultSymbols)

        val timeframes = listOf("1m", "5m", "1h", "1d")
        val now = System.currentTimeMillis()

        defaultSymbols.forEach { sym ->
            timeframes.forEach { tf ->
                val intervalMs = when (tf) {
                    "1m" -> 60_000L
                    "5m" -> 300_000L
                    "1h" -> 3600_000L
                    else -> 86400_000L
                }

                val candlesCount = 60
                val generated = ArrayList<HistoricalCandleEntity>()
                var currentPrice = sym.price - (sym.price * (Random.nextDouble() * 0.1 - 0.05))

                for (i in candlesCount downTo 1) {
                    val candleTime = now - (i * intervalMs)
                    val volatility = when (sym.type) {
                        "crypto" -> 0.025
                        "equity" -> 0.012
                        else -> 0.006
                    }
                    val change = currentPrice * (Random.nextDouble() - 0.49) * volatility
                    val open = currentPrice
                    val close = currentPrice + change
                    val high = maxOf(open, close) + (Math.abs(change) * Random.nextDouble() * 0.3)
                    val low = minOf(open, close) - (Math.abs(change) * Random.nextDouble() * 0.3)
                    
                    generated.add(HistoricalCandleEntity(
                        symbol = sym.symbol,
                        timeframe = tf,
                        timestamp = candleTime,
                        open = (Math.round(open * 100.0) / 100.0),
                        high = (Math.round(high * 100.0) / 100.0),
                        low = (Math.round(low * 100.0) / 100.0),
                        close = (Math.round(close * 100.0) / 100.0),
                        volume = Random.nextLong(1000, 50000)
                    ))

                    currentPrice = close
                }
                dao.insertCandles(generated)
            }
        }

        val seedNews = listOf(
            FinancialNewsEntity(
                title = "Federal Reserve Hints at Possible Rate Cut by Autumn",
                summary = "Chairman Jerome Powell commented on decelerating inflation trends, suggesting macroeconomic factors could trigger rate easements soon.",
                source = "Bloomberg",
                sentiment = "POSITIVE",
                relevanceScore = 0.92,
                timestamp = now - 3600_000L * 2,
                tickerTag = "market-wide"
            ),
            FinancialNewsEntity(
                title = "Apple Unveils State-of-the-Art Neural Processing M6 Chips",
                summary = "At its developer seminar, Apple showcased the next-generation M6 processor, custom designed for real-time mobile generative AI workloads.",
                source = "WSJ",
                sentiment = "POSITIVE",
                relevanceScore = 0.88,
                timestamp = now - 3600_000L * 4,
                tickerTag = "AAPL"
            ),
            FinancialNewsEntity(
                title = "Tesla Vehicle Deliveries Jump 14% Q-o-Q, Beating Estimates",
                summary = "Gigafactory outputs coupled with premium tax credit conversions led to stronger global Model Y shipments, propelling investor confidence.",
                source = "Reuters",
                sentiment = "POSITIVE",
                relevanceScore = 0.95,
                timestamp = now - 3600_000L * 5,
                tickerTag = "TSLA"
            ),
            FinancialNewsEntity(
                title = "Microstrategy Acquires Extra 10,000 Bitcoins as Asset Value Surges",
                summary = "The enterprise firm leveraged convertible debt pools to supplement corporate reserves, driving Bitcoin's institutional liquidity indexes.",
                source = "Financial Times",
                sentiment = "POSITIVE",
                relevanceScore = 0.81,
                timestamp = now - 3600_000L * 8,
                tickerTag = "BTC"
            ),
            FinancialNewsEntity(
                title = "Antitrust Regulators Initiate Scrutiny Into High-End Graphics Chip Contracts",
                summary = "The Justice Department announced preliminary guidelines targeting hyper-scaler allocations of state-of-the-art server cores.",
                source = "CNBC",
                sentiment = "NEGATIVE",
                relevanceScore = 0.86,
                timestamp = now - 3600_000L * 10,
                tickerTag = "NVDA"
            ),
            FinancialNewsEntity(
                title = "Cloud Infrastructure Spending Flatlines Amid Enterprise Efficiency Reviews",
                summary = "Major corporate buyers are auditing cloud storage tiers, triggering marginal margin projections for AWS, Azure, and Google Cloud.",
                source = "MarketWatch",
                sentiment = "NEUTRAL",
                relevanceScore = 0.74,
                timestamp = now - 3600_000L * 14,
                tickerTag = "AMZN"
            ),
            FinancialNewsEntity(
                title = "FTSE Index Tracks Higher Supported by Energy and Utility Dividend Safe-Havens",
                summary = "European buyers shifted defensive cash flows toward high-yield UK staples in response to sovereign debt currency fluctuating sessions.",
                source = "London Times",
                sentiment = "POSITIVE",
                relevanceScore = 0.68,
                timestamp = now - 3600_000L * 18,
                tickerTag = ".FTSE"
            ),
            FinancialNewsEntity(
                title = "Microsoft and OpenAI Expansion Plans Blocked by Regional Power Constraints",
                summary = "The planned supercluster data facilities face local grid constraints, pushing back deployment targets for upgraded model iterations.",
                source = "The Verge",
                sentiment = "NEGATIVE",
                relevanceScore = 0.82,
                timestamp = now - 3600_000L * 24,
                tickerTag = "MSFT"
            )
        )
        dao.insertNews(seedNews)
    }

    // Direct thread-safe API database accessors
    suspend fun getAllSymbols(): List<MarketSymbolEntity> {
        return dao.getAllSymbols()
    }

    suspend fun getCandles(symbol: String, timeframe: String): List<HistoricalCandleEntity> {
        return dao.getCandles(symbol, timeframe)
    }

    suspend fun getNewsForSymbol(symbol: String): List<FinancialNewsEntity> {
        return dao.getNewsForSymbol(symbol)
    }

    suspend fun getAllNews(): List<FinancialNewsEntity> {
        return dao.getAllNews()
    }

    suspend fun getSymbolCurrentCached(symbol: String): MarketSymbolEntity? {
        return symbolCache[symbol] ?: dao.getSymbol(symbol)
    }

    suspend fun addNewSymbol(entity: MarketSymbolEntity) {
        dao.insertSymbols(listOf(entity))
        symbolCache[entity.symbol] = entity
    }

    suspend fun insertCustomNews(news: FinancialNewsEntity) {
        dao.insertSingleNews(news)
    }

    suspend fun shutdown() {
        simulationJob?.cancel()
        scope.cancel()
    }
}

// Simple Dispatcher Provider to support easy testing
object DispatchScopeProvider {
    var dispatcher: CoroutineDispatcher = Dispatchers.IO
}
