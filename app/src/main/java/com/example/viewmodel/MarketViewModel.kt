package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.db.*
import com.example.engine.ApiRequestLog
import com.example.engine.MarketDataEngine
import com.example.server.MarketServer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val database = MarketDatabase.getDatabase(application)
    private val dao = database.marketDao()
    
    val engine = MarketDataEngine(application)
    private var server = MarketServer(engine, 8080)

    // Flow from Room for symbols layout
    val symbols: StateFlow<List<MarketSymbolEntity>> = dao.getAllSymbolsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Flow from Room for latest news
    val newsFeed: StateFlow<List<FinancialNewsEntity>> = dao.getAllNewsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // API logs and metadata from engine
    val connectionLogs: StateFlow<List<ApiRequestLog>> = engine.connectionLogs
    val activeWebSocketCount: StateFlow<Int> = engine.activeWebSocketCount
    val updateFrequencyMs: StateFlow<Long> = engine.updateFrequencyMs

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    // Interactive customizer states for sandbox UI
    val custType = MutableStateFlow("mini-card") // mini-card, stock-ticker, full-chart, company-info, news-feed
    val custSymbol = MutableStateFlow("AAPL")
    val custTheme = MutableStateFlow("dark")
    val custTimeframe = MutableStateFlow("1d")

    init {
        // Start server on startup
        startServer()
    }

    fun startServer() {
        if (_isServerRunning.value) return
        try {
            server = MarketServer(engine, _serverPort.value)
            server.start()
            _isServerRunning.value = true
        } catch (e: Exception) {
            _isServerRunning.value = false
        }
    }

    fun stopServer() {
        server.stop()
        _isServerRunning.value = false
    }

    fun changePort(port: Int) {
        stopServer()
        _serverPort.value = port.coerceIn(1024, 65535)
        startServer()
    }

    fun setUpdateSpeed(ms: Long) {
        engine.setUpdateFrequency(ms)
    }

    fun insertCustomSymbol(
        symbol: String,
        name: String,
        sector: String,
        price: Double,
        exchange: String,
        mcap: Long,
        pe: Double,
        yield: Double,
        type: String
    ) {
        viewModelScope.launch {
            val entity = MarketSymbolEntity(
                symbol = symbol.uppercase().trim(),
                name = name.trim(),
                sector = sector.trim(),
                price = price,
                change = 0.0,
                percentChange = 0.0,
                volume = 10000L,
                exchange = exchange.trim(),
                marketCap = mcap,
                peRatio = pe,
                dividendYield = yield,
                type = type.trim()
            )
            engine.addNewSymbol(entity)
        }
    }

    fun insertCustomNewsStory(
        title: String,
        summary: String,
        source: String,
        sentiment: String,
        relevanceScore: Double,
        ticker: String
    ) {
        viewModelScope.launch {
            val news = FinancialNewsEntity(
                title = title.trim(),
                summary = summary.trim(),
                source = source.trim(),
                sentiment = sentiment.trim().uppercase(),
                relevanceScore = relevanceScore.coerceIn(0.0, 1.0),
                timestamp = System.currentTimeMillis(),
                tickerTag = ticker.trim().uppercase()
            )
            engine.insertCustomNews(news)
        }
    }

    override fun onCleared() {
        super.onCleared()
        server.stop()
        viewModelScope.launch {
            engine.shutdown()
        }
    }
}

class MarketViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarketViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MarketViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
