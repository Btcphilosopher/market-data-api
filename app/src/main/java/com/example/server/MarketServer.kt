package com.example.server

import android.util.Log
import com.example.db.MarketSymbolEntity
import com.example.db.HistoricalCandleEntity
import com.example.db.FinancialNewsEntity
import com.example.engine.MarketDataEngine
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class MarketServer(
    private val engine: MarketDataEngine,
    val port: Int = 8080
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    
    // Thread-safe map of active WebSocket connections, using OutputStreams
    private val wsClients = ConcurrentHashMap.newKeySet<OutputStream>()

    private var serverJob: Job? = null
    private var wssForwardJob: Job? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d("MarketServer", "Server started on port $port")
                
                // Start WebSocket forwarding loop
                startWssBroadcasting()

                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClientConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e("MarketServer", "Server exception: ${e.message}", e)
                isRunning = false
            }
        }
    }

    private fun startWssBroadcasting() {
        wssForwardJob?.cancel()
        wssForwardJob = scope.launch {
            engine.liveTickFlow.collect { tick ->
                // Construct compact JSON tick
                val json = """
                    {
                      "symbol": "${tick.symbol}",
                      "name": "${tick.name.replace("\"", "\\\"")}",
                      "price": ${tick.price},
                      "change": ${tick.change},
                      "percentChange": ${tick.percentChange},
                      "volume": ${tick.volume},
                      "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent().replace("\n", "").replace(" ", "")
                
                // Broadcast to active websocket outputs
                val iterator = wsClients.iterator()
                while (iterator.hasNext()) {
                    val clientOut = iterator.next()
                    try {
                        sendWebSocketTextFrame(clientOut, json)
                    } catch (e: Exception) {
                        // Connection lost
                        iterator.remove()
                        engine.decrementWebSockets()
                    }
                }
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        val startTime = System.currentTimeMillis()
        val clientIp = socket.inetAddress.hostAddress ?: "127.0.0.1"
        var path = "/"
        var method = "GET"
        var status = 200

        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, "UTF-8"))
            
            // Read HTTP request line
            val firstLine = reader.readLine() ?: return
            val requestParts = firstLine.split(" ")
            if (requestParts.size < 2) {
                sendHttpError(output, 400, "Bad Request")
                return
            }
            method = requestParts[0]
            val fullUrl = requestParts[1]
            path = fullUrl.split("?")[0]

            // Read headers to look for Sec-WebSocket-Key and Upgrade headers
            var isWsUpgrade = false
            var wsKey = ""
            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val headerParts = line.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val keyName = headerParts[0].trim().lowercase()
                    val valName = headerParts[1].trim()
                    if (keyName == "upgrade" && valName.lowercase() == "websocket") {
                        isWsUpgrade = true
                    }
                    if (keyName == "sec-websocket-key") {
                        wsKey = valName
                    }
                }
                line = reader.readLine()
            }

            // Route standard requests
            if (method == "OPTIONS") {
                // Return standard CORS success preflight
                sendCorsOk(output)
                val duration = System.currentTimeMillis() - startTime
                engine.logConnection(clientIp, method, fullUrl, 204, duration)
                socket.close()
                return
            }

            if (isWsUpgrade && path == "/api/v1/stream/prices" && wsKey.isNotEmpty()) {
                // Handle WebSocket upgrade
                val acceptKey = getWebSocketAcceptVal(wsKey)
                val responseHeaders = """
                    HTTP/1.1 101 Switching Protocols
                    Upgrade: websocket
                    Connection: Upgrade
                    Sec-WebSocket-Accept: $acceptKey
                    
                    
                """.trimIndent().replace("\n", "\r\n")
                
                output.write(responseHeaders.toByteArray(Charsets.UTF_8))
                output.flush()

                wsClients.add(output)
                engine.incrementWebSockets()

                val duration = System.currentTimeMillis() - startTime
                engine.logConnection(clientIp, "WSS-UPGRADE", path, 101, duration)

                // Simple ping-pong loop to keep client socket alive
                try {
                    while (isRunning && socket.isConnected && !socket.isClosed) {
                        // Standard WebSocket framing read to detect close
                        val b0 = input.read()
                        if (b0 == -1) break // EOF
                        val opcode = b0 and 0x0F
                        if (opcode == 8) {
                            // Close frame
                            break
                        }
                        // Read and discard client frames for low data rate
                        val b1 = input.read()
                        if (b1 == -1) break
                        val hasMask = (b1 and 0x80) != 0
                        val payLen = b1 and 0x7F
                        if (payLen == 126) {
                            input.read(); input.read()
                        } else if (payLen == 127) {
                            repeat(8) { input.read() }
                        }
                        if (hasMask) {
                            repeat(4) { input.read() } // Masking key
                        }
                        // Read payload
                        repeat(payLen) { input.read() }
                    }
                } catch (e: Exception) {
                    // Closed
                } finally {
                    wsClients.remove(output)
                    engine.decrementWebSockets()
                    socket.close()
                }
                return
            }

            // Regular REST/Widget routes
            var responseData: String
            var contentType = "application/json"

            when {
                path.startsWith("/api/v1/price/") -> {
                    val symbol = path.substringAfter("/api/v1/price/").trim().uppercase()
                    val symData = engine.getSymbolCurrentCached(symbol)
                    if (symData != null) {
                        responseData = formatSymbolJson(symData)
                    } else {
                        status = 404
                        responseData = """{"error":"Symbol $symbol not found"}"""
                    }
                }
                path == "/api/v1/ticker" -> {
                    val symbols = engine.getAllSymbols()
                    responseData = formatTickerListJson(symbols)
                }
                path.startsWith("/api/v1/chart/") -> {
                    val remainder = path.substringAfter("/api/v1/chart/").trim()
                    val (symbol, timeframe) = parseChartParams(remainder, fullUrl)
                    val candleList = engine.getCandles(symbol, timeframe)
                    responseData = formatCandlesJson(candleList)
                }
                path.startsWith("/api/v1/news/") -> {
                    val symbol = path.substringAfter("/api/v1/news/").trim().uppercase()
                    val newsList = if (symbol.isEmpty() || symbol == "ALL" || symbol == "MARKET-WIDE") {
                        engine.getAllNews()
                    } else {
                        engine.getNewsForSymbol(symbol)
                    }
                    responseData = formatNewsListJson(newsList)
                }
                path.startsWith("/api/v1/company/") -> {
                    val symbol = path.substringAfter("/api/v1/company/").trim().uppercase()
                    val symData = engine.getSymbolCurrentCached(symbol)
                    if (symData != null) {
                        responseData = formatCompanyFundamentalsJson(symData)
                    } else {
                        status = 404
                        responseData = """{"error":"Company $symbol fundamentals not found"}"""
                    }
                }
                path == "/api/v1/widget/render" -> {
                    contentType = "text/html; charset=UTF-8"
                    responseData = generateHtmlWidget(fullUrl)
                }
                else -> {
                    status = 404
                    responseData = """{"error":"Route not found - endpoints: /api/v1/price/{symbol}, /api/v1/stream/prices, /api/v1/chart/{symbol}, /api/v1/news/{symbol}, /api/v1/company/{symbol}, /api/v1/ticker, /api/v1/widget/render"}"""
                }
            }

            // Write regular HTTP response
            val responseBytes = responseData.toByteArray(Charsets.UTF_8)
            val responseHeaders = """
                HTTP/1.1 $status ${getHttpStatusText(status)}
                Content-Type: $contentType
                Content-Length: ${responseBytes.size}
                Access-Control-Allow-Origin: *
                Access-Control-Allow-Methods: GET, OPTIONS
                Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With
                Connection: close
                
                
            """.trimIndent().replace("\n", "\r\n")

            output.write(responseHeaders.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
            socket.close()

            val duration = System.currentTimeMillis() - startTime
            engine.logConnection(clientIp, method, fullUrl, status, duration)

        } catch (e: Exception) {
            Log.e("MarketServer", "Error parsing client request: ${e.message}")
            try { socket.close() } catch (ex: Exception) {}
            val duration = System.currentTimeMillis() - startTime
            engine.logConnection(clientIp, method, path, 500, duration)
        }
    }

    private fun parseChartParams(remainder: String, fullUrl: String): Pair<String, String> {
        var symbol = remainder.split("?")[0].uppercase()
        var timeframe = "1d"
        val tf = getQueryParam(fullUrl, "timeframe") ?: getQueryParam(fullUrl, "tf")
        if (tf != null) {
            timeframe = tf.lowercase()
        }
        if (symbol.contains("/")) {
            symbol = symbol.substringBefore("/")
        }
        return Pair(symbol, timeframe)
    }

    // Pure Kotlin query parameters extractor - zero dependencies, works on any platform!
    private fun getQueryParam(url: String, name: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart == -1) return null
        val query = url.substring(queryStart + 1)
        val pairs = query.split('&')
        for (pair in pairs) {
            val kv = pair.split('=')
            if (kv.size == 2 && kv[0] == name) {
                return kv[1]
            }
        }
        return null
    }

    private fun getHttpStatusText(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun sendHttpError(output: OutputStream, status: Int, msg: String) {
        val json = """{"error":"$msg"}"""
        val responseBytes = json.toByteArray(Charsets.UTF_8)
        val headers = """
            HTTP/1.1 $status $msg
            Content-Type: application/json
            Content-Length: ${responseBytes.size}
            Access-Control-Allow-Origin: *
            Connection: close
            
            
        """.trimIndent().replace("\n", "\r\n")
        try {
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        } catch (e: Exception) {}
    }

    private fun sendCorsOk(output: OutputStream) {
        val headers = """
            HTTP/1.1 204 No Content
            Access-Control-Allow-Origin: *
            Access-Control-Allow-Methods: GET, POST, OPTIONS, PUT, DELETE
            Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With, Sec-WebSocket-Protocol, Sec-WebSocket-Extensions, Sec-WebSocket-Key, Sec-WebSocket-Version
            Access-Control-Max-Age: 86400
            Connection: close
            
            
        """.trimIndent().replace("\n", "\r\n")
        try {
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {}
    }

    // JSON Formatting Helpers without requiring Moshi setup inside request threads for ultimate speed
    private fun formatSymbolJson(s: MarketSymbolEntity): String = """
        {
          "symbol": "${s.symbol}",
          "name": "${s.name.replace("\"", "\\\"")}",
          "exchange": "${s.exchange}",
          "sector": "${s.sector}",
          "price": ${s.price},
          "change": ${s.change},
          "percentChange": ${s.percentChange},
          "volume": ${s.volume},
          "type": "${s.type}"
        }
    """.trimIndent()

    private fun formatCompanyFundamentalsJson(s: MarketSymbolEntity): String = """
        {
          "symbol": "${s.symbol}",
          "name": "${s.name.replace("\"", "\\\"")}",
          "sector": "${s.sector}",
          "exchange": "${s.exchange}",
          "marketCap": ${s.marketCap},
          "peRatio": ${s.peRatio},
          "dividendYield": ${s.dividendYield},
          "type": "${s.type}"
        }
    """.trimIndent()

    private fun formatTickerListJson(list: List<MarketSymbolEntity>): String {
        return list.joinToString(prefix = "[", postfix = "]", separator = ",") { formatSymbolJson(it) }
    }

    private fun formatCandlesJson(list: List<HistoricalCandleEntity>): String {
        return list.joinToString(prefix = "[", postfix = "]", separator = ",") { c ->
            """{"timestamp":${c.timestamp},"open":${c.open},"high":${c.high},"low":${c.low},"close":${c.close},"volume":${c.volume}}"""
        }
    }

    private fun formatNewsListJson(list: List<FinancialNewsEntity>): String {
        return list.joinToString(prefix = "[", postfix = "]", separator = ",") { n ->
            """
            {
              "id": ${n.id},
              "title": "${n.title.replace("\"", "\\\"")}",
              "summary": "${n.summary.replace("\"", "\\\"")}",
              "source": "${n.source}",
              "sentiment": "${n.sentiment}",
              "relevanceScore": ${n.relevanceScore},
              "timestamp": ${n.timestamp},
              "tickerTag": "${n.tickerTag}"
            }
            """.trimIndent().replace("\n", "").replace(" ", " ")
        }
    }

    // ==========================================
    // EXTENDED EMBEDDABLE WIDGET GENERATION (CRITICAL)
    // ==========================================
    private fun generateHtmlWidget(url: String): String {
        val type = getQueryParam(url, "type") ?: "mini-card"
        val symbol = (getQueryParam(url, "symbol") ?: "AAPL").uppercase()
        val theme = getQueryParam(url, "theme") ?: "dark"
        val timeframe = getQueryParam(url, "timeframe") ?: "1d"

        val isDark = theme == "dark"
        val bgColor = if (isDark) "#111726" else "#F4F6FA"
        val cardBg = if (isDark) "#1C2434" else "#FFFFFF"
        val textColor = if (isDark) "#FFFFFF" else "#0F172A"
        val subColor = if (isDark) "#94A3B8" else "#64748B"
        val borderColor = if (isDark) "#2E3B52" else "#E2E8F0"
        val wssUrl = "ws://localhost:$port/api/v1/stream/prices"
        val restUrl = "http://localhost:$port/api/v1"

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Embeddable Market Widget</title>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
            <style>
                * {
                    box-sizing: border-box;
                    margin: 0;
                    padding: 0;
                    font-family: 'Inter', -apple-system, sans-serif;
                }
                body {
                    background: transparent;
                    color: $textColor;
                    font-size: 14px;
                    overflow: hidden;
                }
                .widget-container {
                    background: $cardBg;
                    border: 1px solid $borderColor;
                    border-radius: 12px;
                    padding: 16px;
                    width: 100%;
                    height: 100%;
                    display: flex;
                    flex-direction: column;
                    justify-content: space-between;
                    box-shadow: 0 4px 12px rgba(0,0,0,0.05);
                }
                .green { color: #10B981; }
                .red { color: #EF4444; }
                .flex-row { display: flex; align-items: center; justify-content: space-between; }
                
                /* ========================================== */
                /* TICKER BAR STYLE */
                /* ========================================== */
                .ticker-bar {
                    background: $cardBg;
                    border-bottom: 1px solid $borderColor;
                    height: 48px;
                    overflow: hidden;
                    white-space: nowrap;
                    display: flex;
                    align-items: center;
                    width: 100%;
                    border-radius: 0;
                    box-shadow: none;
                    padding: 0;
                }
                .ticker-scroller {
                    display: inline-block;
                    animation: marquee 25s linear infinite;
                    padding-left: 100%;
                }
                .ticker-item {
                    display: inline-flex;
                    align-items: center;
                    margin-right: 32px;
                    font-size: 13px;
                    font-weight: 500;
                }
                .ticker-symbol {
                    color: $textColor;
                    font-weight: 700;
                    margin-right: 6px;
                }
                .ticker-price {
                    color: $subColor;
                    margin-right: 6px;
                }
                @keyframes marquee {
                    0% { transform: translate3d(0, 0, 0); }
                    100% { transform: translate3d(-100%, 0, 0); }
                }

                /* ========================================== */
                /* MINI CARD STYLE */
                /* ========================================== */
                .mini-card-header {
                    font-size: 11px;
                    text-transform: uppercase;
                    letter-spacing: 0.05em;
                    color: $subColor;
                    font-weight: 600;
                    margin-bottom: 4px;
                }
                .mini-name {
                    font-size: 16px;
                    font-weight: 700;
                }
                .mini-price-row {
                    margin-top: 10px;
                    margin-bottom: 4px;
                }
                .mini-price {
                    font-size: 28px;
                    font-weight: 700;
                    letter-spacing: -0.02em;
                }
                .mini-pct {
                    font-size: 14px;
                    font-weight: 600;
                    display: flex;
                    align-items: center;
                    gap: 4px;
                }

                /* ========================================== */
                /* FULL CHART STYLE */
                /* ========================================== */
                .chart-area {
                    flex-grow: 1;
                    height: 140px;
                    margin: 12px 0;
                    position: relative;
                }
                canvas {
                    width: 100%;
                    height: 100%;
                }
                .chart-title {
                    font-size: 18px;
                    font-weight: 700;
                }
                .chart-meta {
                    color: $subColor;
                    font-size: 12px;
                }

                /* ========================================== */
                /* COMPANY INFO STYLE */
                /* ========================================== */
                .company-info-grid {
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 12px;
                    margin-top: 10px;
                }
                .info-item {
                    border-bottom: 1px solid $borderColor;
                    padding-bottom: 6px;
                }
                .info-label {
                    font-size: 11px;
                    color: $subColor;
                    font-weight: 500;
                    text-transform: uppercase;
                }
                .info-val {
                    font-size: 14px;
                    font-weight: 600;
                }

                /* ========================================== */
                /* NEWS STYLE */
                /* ========================================== */
                .news-list {
                    display: flex;
                    flex-direction: column;
                    gap: 10px;
                    overflow-y: auto;
                    height: 100%;
                    max-height: 250px;
                    padding-right: 4px;
                }
                .news-item {
                    border-bottom: 1px dashed $borderColor;
                    padding-bottom: 8px;
                }
                .news-title {
                    font-weight: 600;
                    font-size: 13px;
                    margin-bottom: 4px;
                    line-height: 1.4;
                }
                .news-meta {
                    font-size: 11px;
                    color: $subColor;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .tag {
                    font-size: 9px;
                    padding: 1px 4px;
                    border-radius: 4px;
                    font-weight: 700;
                }
                .tag-pos { background: rgba(16,185,129,0.1); color: #10B981; }
                .tag-neg { background: rgba(239,68,68,0.1); color: #EF4444; }
                .tag-neu { background: rgba(148,163,184,0.1); color: $subColor; }
            </style>
        </head>
        <body>

        <!-- ========================================== -->
        <!-- WIDGET CONDITIONAL RENDERING -->
        <!-- ========================================== -->
        ${if (type == "stock-ticker") {
            """
            <div class="ticker-bar" id="ticker-bar-container">
                <div class="ticker-scroller" id="ticker-target">
                     <!-- Populated on startup -->
                </div>
            </div>
            """
        } else if (type == "mini-card") {
            """
            <div class="widget-container">
                <div>
                    <div class="flex-row">
                        <div class="mini-card-header" id="widget-exchange">NASDAQ</div>
                        <div class="tag tag-pos" style="padding:2px 6px;" id="widget-symbol-tag">$symbol</div>
                    </div>
                    <div class="mini-name" id="widget-name">$symbol Inc.</div>
                </div>
                <div class="flex-row mini-price-row">
                    <div class="mini-price" id="widget-price">--</div>
                    <div class="mini-pct green" id="widget-change">--</div>
                </div>
                <div class="flex-row" style="font-size: 10px; color: $subColor;">
                    <div>Live Streaming Real-time</div>
                    <div id="widget-status" class="green">● Connected</div>
                </div>
            </div>
            """
        } else if (type == "full-chart") {
            """
            <div class="widget-container" style="height:250px;">
                <div class="flex-row">
                    <div>
                        <div class="chart-title"><span id="chart-symbol-name">$symbol</span> <span id="chart-live-price" style="font-size:16px;">--</span></div>
                        <div class="chart-meta">Timeframe: ${timeframe.uppercase()} • Volume: <span id="chart-volume">--</span></div>
                    </div>
                    <span class="tag tag-pos" style="padding: 2px 6px" id="chart-change-pill">--</span>
                </div>
                <div class="chart-area">
                    <canvas id="chart-canvas"></canvas>
                </div>
                <div class="flex-row" style="font-size:10px; color:$subColor;">
                    <div>Interactive Candle OHLC Analytics</div>
                    <div>WSS Link Live</div>
                </div>
            </div>
            """
        } else if (type == "company-info") {
            """
            <div class="widget-container" style="height:220px;">
                <div>
                    <div class="mini-card-header">Company Profile</div>
                    <div class="mini-name" id="c-name">$symbol Fundamentals</div>
                </div>
                <div class="company-info-grid">
                    <div class="info-item"><div class="info-label">Sector</div><div id="c-sector" class="info-val">--</div></div>
                    <div class="info-item"><div class="info-label">Market Cap</div><div id="c-cap" class="info-val">--</div></div>
                    <div class="info-item"><div class="info-label">P/E Ratio</div><div id="c-pe" class="info-val">--</div></div>
                    <div class="info-item"><div class="info-label">Div Yield</div><div id="c-div" class="info-val">--</div></div>
                </div>
                <div style="font-size:10px; color: $subColor; text-align:right;">Apex Financial Infra API</div>
            </div>
            """
        } else if (type == "news-feed") {
            """
            <div class="widget-container" style="height:280px;">
                <div class="mini-card-header" style="margin-bottom: 8px;">Latest Financial Sentiment: $symbol</div>
                <div class="news-list" id="news-container">
                     <!-- Loaded via REST -->
                </div>
                <div style="font-size:10px; color: $subColor; margin-top:4px;">Auto-Updated via Indexer</div>
            </div>
            """
        } else ""}

        <script>
            // Establish shared references
            const symbol = '$symbol';
            const wssUrl = '$wssUrl';
            const restUrl = '$restUrl';

            // Establish WebSocket Live Subscription
            let ws;
            function initWebSocket() {
                ws = new WebSocket(wssUrl);
                ws.onopen = () => {
                    const statusVal = document.getElementById('widget-status');
                    if (statusVal) {
                        statusVal.textContent = '● Connected';
                        statusVal.className = 'green';
                    }
                };
                ws.onmessage = (event) => {
                    const tick = JSON.parse(event.data);
                    if (tick.symbol === symbol) {
                        updateWidgetData(tick);
                    }
                    // Handle scrolling marquee updates
                    if (document.getElementById('ticker-bar-container')) {
                        updateMarqueeTickerItem(tick);
                    }
                };
                ws.onclose = () => {
                    const statusVal = document.getElementById('widget-status');
                    if (statusVal) {
                        statusVal.textContent = '○ Reconnecting...';
                        statusVal.className = 'red';
                    }
                    setTimeout(initWebSocket, 2000); // Auto-reconnect
                };
            }

            // Route updates
            function updateWidgetData(tick) {
                const elPrice = document.getElementById('widget-price');
                const elChange = document.getElementById('widget-change');
                const elName = document.getElementById('widget-name');
                const elEx = document.getElementById('widget-exchange');
                const chartLivePrice = document.getElementById('chart-live-price');
                const chartChg = document.getElementById('chart-change-pill');

                let formattedPrice = tick.price.toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2});
                formattedPrice = '$' + formattedPrice;

                const sign = tick.change >= 0 ? '+' : '';
                const pctText = sign + tick.change.toFixed(2) + ' (' + sign + tick.percentChange.toFixed(2) + '%)';
                const isPos = tick.change >= 0;

                if (elPrice) elPrice.textContent = formattedPrice;
                if (elChange) {
                    elChange.textContent = pctText;
                    elChange.className = 'mini-pct ' + (isPos ? 'green' : 'red');
                }
                if (elName) elName.textContent = tick.name;

                if (chartLivePrice) chartLivePrice.textContent = formattedPrice;
                if (chartChg) {
                    chartChg.textContent = pctText;
                    chartChg.className = 'tag ' + (isPos ? 'tag-pos' : 'tag-neg');
                }
            }

            // Pull startup REST statistics
            async function fetchData() {
                try {
                    // Pull basic statistics
                    const res = await fetch(restUrl + '/price/' + symbol);
                    if (res.ok) {
                        const tick = await res.json();
                        updateWidgetData(tick);
                    }

                    // Pull fundamentals if needed
                    const elSec = document.getElementById('c-sector');
                    if (elSec) {
                        const profileRes = await fetch(restUrl + '/company/' + symbol);
                        if (profileRes.ok) {
                            const p = await profileRes.json();
                            document.getElementById('c-name').textContent = p.name;
                            document.getElementById('c-sector').textContent = p.sector;
                            document.getElementById('c-cap').textContent = (p.marketCap / 1e9).toFixed(1) + ' Billion';
                            document.getElementById('c-pe').textContent = p.peRatio === 0 ? 'N/A' : p.peRatio.toFixed(1);
                            document.getElementById('c-div').textContent = p.dividendYield.toFixed(2) + '%';
                        }
                    }

                    // Pull News list
                    const newsContainer = document.getElementById('news-container');
                    if (newsContainer) {
                        const newsRes = await fetch(restUrl + '/news/' + symbol);
                        if (newsRes.ok) {
                            const news = await newsRes.json();
                            newsContainer.innerHTML = '';
                            if (news.length === 0) {
                                newsContainer.innerHTML = '<div style="color:#94a3b8; font-size:12px;">No news currently.</div>';
                            }
                            news.forEach(n => {
                                const stTag = n.sentiment === 'POSITIVE' ? 'tag-pos' : (n.sentiment === 'NEGATIVE' ? 'tag-neg' : 'tag-neu');
                                const itemHtml = `
                                    <div class="news-item">
                                        <div class="news-title">${'$'}{n.title}</div>
                                        <div class="news-meta">
                                            <span>${'$'}{n.source}</span>
                                            <span class="tag ${'$'}{stTag}">${'$'}{n.sentiment}</span>
                                            <span style="font-size:10px;">Relevance Score: ${'$'}{n.relevanceScore.toFixed(2)}</span>
                                        </div>
                                    </div>
                                `;
                                newsContainer.innerHTML += itemHtml;
                            });
                        }
                    }

                    // Render candles / historical chart if needed
                    const canvas = document.getElementById('chart-canvas');
                    if (canvas) {
                        const chartRes = await fetch(restUrl + '/chart/' + symbol + '?timeframe=' + '$timeframe');
                        if (chartRes.ok) {
                            const candles = await chartRes.json();
                            if (candles.length > 0) {
                                drawCandlestickChart(canvas, candles);
                                const lastCandle = candles[candles.length - 1];
                                document.getElementById('chart-volume').textContent = lastCandle.volume.toLocaleString();
                            }
                        }
                    }

                    // Load index ticker bar
                    const tickerTarget = document.getElementById('ticker-target');
                    if (tickerTarget) {
                        const tkRes = await fetch(restUrl + '/ticker');
                        if (tkRes.ok) {
                            const tickList = await tkRes.json();
                            tickerTarget.innerHTML = '';
                            tickList.forEach(t => {
                                const sign = t.change >= 0 ? '+' : '';
                                const colorClass = t.change >= 0 ? 'green' : 'red';
                                tickerTarget.innerHTML += `
                                    <div class="ticker-item" id="marquee-\${t.symbol}">
                                        <span class="ticker-symbol">\${t.symbol}</span>
                                        <span class="ticker-price">\$\${t.price.toLocaleString(undefined, {minimumFractionDigits:2})}</span>
                                        <span class="\${colorClass}">\${sign}\${t.percentChange.toFixed(2)}%</span>
                                    </div>
                                `;
                            });
                        }
                    }

                } catch (e) {
                    console.error("Failed to load startup REST states", e);
                }
            }

            function updateMarqueeTickerItem(tick) {
                const item = document.getElementById('marquee-' + tick.symbol);
                if (item) {
                    const sign = tick.change >= 0 ? '+' : '';
                    const colorClass = tick.change >= 0 ? 'green' : 'red';
                    item.innerHTML = `
                        <span class="ticker-symbol">\${tick.symbol}</span>
                        <span class="ticker-price">\$\${tick.price.toLocaleString(undefined, {minimumFractionDigits:2})}</span>
                        <span class="\${colorClass}">\${sign}\${tick.percentChange.toFixed(2)}%</span>
                    `;
                }
            }

            function drawCandlestickChart(canvas, candles) {
                const ctx = canvas.getContext('2d');
                const rect = canvas.getBoundingClientRect();
                canvas.width = rect.width * window.devicePixelRatio;
                canvas.height = rect.height * window.devicePixelRatio;
                ctx.scale(window.devicePixelRatio, window.devicePixelRatio);

                const width = rect.width;
                const height = rect.height;

                ctx.clearRect(0, 0, width, height);

                let minVal = Infinity;
                let maxVal = -Infinity;
                for (let c of candles) {
                    if (c.low < minVal) minVal = c.low;
                    if (c.high > maxVal) maxVal = c.high;
                }

                const priceRange = maxVal - minVal;
                minVal -= priceRange * 0.05;
                maxVal += priceRange * 0.05;

                const candleWidth = width / candles.length;
                
                ctx.strokeStyle = '#2E3B52';
                ctx.lineWidth = 0.5;
                for (let i = 1; i <= 3; i++) {
                    const gridY = (height / 4) * i;
                    ctx.beginPath();
                    ctx.moveTo(0, gridY);
                    ctx.lineTo(width, gridY);
                    ctx.stroke();
                }

                for (let i = 0; i < candles.length; i++) {
                    const c = candles[i];
                    const isGreen = c.close >= c.open;
                    const x = i * candleWidth + candleWidth/2;
                    
                    const openY = height - ((c.open - minVal) / (maxVal - minVal)) * height;
                    const closeY = height - ((c.close - minVal) / (maxVal - minVal)) * height;
                    const highY = height - ((c.high - minVal) / (maxVal - minVal)) * height;
                    const lowY = height - ((c.low - minVal) / (maxVal - minVal)) * height;

                    ctx.strokeStyle = isGreen ? '#10B981' : '#EF4444';
                    ctx.fillStyle = isGreen ? '#10B981' : '#EF4444';
                    ctx.lineWidth = 1.5;

                    ctx.beginPath();
                    ctx.moveTo(x, highY);
                    ctx.lineTo(x, lowY);
                    ctx.stroke();

                    const bodyH = Math.max(Math.abs(closeY - openY), 2.0);
                    const bodyY = Math.min(openY, closeY);
                    const bodyW = candleWidth * 0.7;
                    ctx.fillRect(x - bodyW/2, bodyY, bodyW, bodyH);
                }
            }

            fetchData().then(() => {
                initWebSocket();
            });
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun getWebSocketAcceptVal(key: String): String {
        return try {
            val combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val digest = MessageDigest.getInstance("SHA-1")
            val sha1Bytes = digest.digest(combined.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(sha1Bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    private fun sendWebSocketTextFrame(out: OutputStream, text: String) {
        val payloadBytes = text.toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.write(0x81) // FIN & Text framing opcode
            
            val len = payloadBytes.size
            if (len <= 125) {
                out.write(len)
            } else if (len <= 65535) {
                out.write(126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            } else {
                out.write(127)
                out.write(0); out.write(0); out.write(0); out.write(0)
                out.write((len shr 24) and 0xFF)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            out.write(payloadBytes)
            out.flush()
        }
    }

    fun stop() {
        isRunning = false
        wssForwardJob?.cancel()
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        
        wsClients.clear()
        Log.d("MarketServer", "Server stopped on port $port")
    }
}
