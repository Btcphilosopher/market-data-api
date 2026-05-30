package com.example

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.db.*
import com.example.engine.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DeveloperCockpitScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun DeveloperCockpitScreen(
    modifier: Modifier = Modifier,
    viewModel: MarketViewModel = viewModel(
        factory = MarketViewModelFactory(LocalContext.current.applicationContext as Application)
    )
) {
    var activeTab by remember { mutableStateOf(0) }
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    
    // Theme Colors (Robinhood Lux Dark Vibes)
    val darkCardColor = Color(0xFF1E2638)
    val emeraldColor = Color(0xFF10B981)
    val softGold = Color(0xFFFBBF24)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Ambient dark canvas
    ) {
        // ==========================================
        // INFRASTRUCTURE GLOBAL APP BAR
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = darkCardColor),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Api Logo",
                        tint = emeraldColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "APEX FINANCE INFRA",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "API Gateway Controller & Embed Sandbox",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }

                // Server state indicators
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isServerRunning) Color(0x3310B981) else Color(0x33EF4444))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isServerRunning) emeraldColor else Color.Red)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServerRunning) "PORT $serverPort ACTIVE" else "SERVER STOPPED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isServerRunning) emeraldColor else Color.Red
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (isServerRunning) viewModel.stopServer() else viewModel.startServer()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isServerRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = "Server Power",
                            tint = if (isServerRunning) Color.Red else emeraldColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // ==========================================
        // MAIN COCKPIT VIEWPORT TABS
        // ==========================================
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = Color(0xFF161F30),
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                    color = softGold
                )
            }
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Menu, "Monitor", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("API Monitor", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }},
                modifier = Modifier.testTag("tab_monitor")
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, "Widgets", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Widget Sandbox", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }},
                modifier = Modifier.testTag("tab_sandbox")
            )
            Tab(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, "Publisher", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Publisher Desk", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }},
                modifier = Modifier.testTag("tab_publisher")
            )
        }

        // View render routers
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(Color(0xFF0F172A))
        ) {
            when (activeTab) {
                0 -> ApiMonitorTab(viewModel)
                1 -> WidgetSandboxTab(viewModel)
                2 -> PublisherDeskTab(viewModel)
            }
        }
    }
}

// ==========================================
// MONITOR TAB VIEW
// ==========================================
@Composable
fun ApiMonitorTab(viewModel: MarketViewModel) {
    val symbols by viewModel.symbols.collectAsState()
    val logs by viewModel.connectionLogs.collectAsState()
    val wsClientsCount by viewModel.activeWebSocketCount.collectAsState()
    val updateMs by viewModel.updateFrequencyMs.collectAsState()
    val isRunning by viewModel.isServerRunning.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()

    var selectedSymbolDetail by remember { mutableStateOf<MarketSymbolEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Upper stats metrics panel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Live WS Channels",
                value = "$wsClientsCount Clients",
                icon = Icons.Default.Info,
                color = Color(0xFF60A5FA),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Tick Frequency",
                value = "${updateMs}ms",
                icon = Icons.Default.PlayArrow,
                color = Color(0xFFFBBF24),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Available Indexes",
                value = "${symbols.size} Seeds",
                icon = Icons.Default.List,
                color = Color(0xFF34D399),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Ingestion update speed customization slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ingestion Tick Controller (Real-Time Simulator)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${updateMs}ms / update",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = updateMs.toFloat(),
                    onValueChange = { viewModel.setUpdateSpeed(it.toLong()) },
                    valueRange = 200f..3000f,
                    steps = 10,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFBBF24),
                        activeTrackColor = Color(0xFFFBBF24),
                        inactiveTrackColor = Color(0xFF2E3B52)
                    )
                )
                Text(
                    text = "This sets the speed of our mathematical random-walk fluctuations. Decreasing the interval models a high-frequency trading session.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Real-Time Indexes Bar
        Text(
            text = "LIVE MARKET BENCHMARKS (Updating)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val macroIndicators = symbols.filter { it.type == "index" || it.type == "etf" }
            macroIndicators.forEach { ind ->
                BenchmarkItem(ind) {
                    selectedSymbolDetail = ind
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Main symbols dashboard & live connection outputs side-by-side or stacked in mobile
        Text(
            text = "REAL-TIME SYMBOL REGISTRY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // Show standard list
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val equitiesCrypto = symbols.filter { it.type == "equity" || it.type == "crypto" }
            equitiesCrypto.forEach { s ->
                SymbolListItem(
                    symbol = s,
                    onSelect = { selectedSymbolDetail = s }
                )
            }
        }

        // Symbol detail expanded dialog / sheet simulation
        selectedSymbolDetail?.let { activeSym ->
            val latestSym = symbols.find { it.symbol == activeSym.symbol } ?: activeSym
            AlertDialog(
                onDismissRequest = { selectedSymbolDetail = null },
                containerColor = Color(0xFF161F30),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(latestSym.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("${latestSym.exchange} • ${latestSym.type.uppercase()}", color = Color.Gray, fontSize = 11.sp)
                        }
                        Text(latestSym.symbol, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$${latestSym.price.toLocaleString()}",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            val isPos = latestSym.change >= 0
                            Text(
                                text = (if (isPos) "+" else "") + "${latestSym.change} (${latestSym.percentChange}%)",
                                color = if (isPos) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Divider(color = Color(0xFF2E3B52))

                        // Details list
                        DetailRow("Exchange Listing", latestSym.exchange)
                        DetailRow("Primary Sector", latestSym.sector)
                        DetailRow("Market Cap", "$${(latestSym.marketCap / 1e9).toFixed(1)}B")
                        DetailRow("P/E Ratio", if (latestSym.peRatio == 0.0) "N/A" else latestSym.peRatio.toString())
                        DetailRow("Dividend Yield", "${latestSym.dividendYield}%")
                        DetailRow("Cumulative Volume", latestSym.volume.toLocaleString())
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedSymbolDetail = null }) {
                        Text("Done", color = Color(0xFFFBBF24))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // API Connection Traffic Log Screen (Visual gateway response logger)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "API GATEWAY LOGS (Live Connections Traffic)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = "Port: $serverPort",
                fontSize = 10.sp,
                color = Color(0xFF60A5FA)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2638))
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Code, "Terminal icon", tint = Color.DarkGray, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Awaiting first incoming local request...\nTry fetching http://localhost:8080/api/v1/ticker\nor open the Sandbox tab.",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { log ->
                        ApiLogLine(log)
                    }
                }
            }
        }
    }
}

// ==========================================
// THE SANDBOX PLAYGROUND TAB
// ==========================================
@Composable
fun WidgetSandboxTab(viewModel: MarketViewModel) {
    val items by viewModel.symbols.collectAsState()
    val allNews by viewModel.newsFeed.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()

    val selectedType by viewModel.custType.collectAsState()
    val selectedSymbol by viewModel.custSymbol.collectAsState()
    val selectedTheme by viewModel.custTheme.collectAsState()
    val selectedTimeframe by viewModel.custTimeframe.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val widgetUrl = "http://localhost:$serverPort/api/v1/widget/render?type=$selectedType&symbol=$selectedSymbol&theme=$selectedTheme&timeframe=$selectedTimeframe"
    val scriptEmbedTag = "<iframe src=\"$widgetUrl\" width=\"${if (selectedType == "stock-ticker") "100%" else "380"}\" height=\"${if (selectedType == "stock-ticker") "50" else "260"}\" style=\"border:none; border-radius:12px;\"></iframe>"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30))
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, "Setup Widget Icon", tint = Color(0xFFFBBF24), modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Widget Sandboxing Playground", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Select a model, customize themes, copy deployment tags, and view visual replica runs updating live.", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2638)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Customize Widget Attributes", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)

                Text("WIDGET DISPLAY INTERFACE", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val types = listOf(
                        "mini-card" to "Mini Card",
                        "stock-ticker" to "Moving Bar",
                        "full-chart" to "Candlesticks Chart",
                        "company-info" to "Indices Profile",
                        "news-feed" to "Sentiment News"
                    )
                    types.forEach { (keyword, display) ->
                        FilterChip(
                            selected = selectedType == keyword,
                            onClick = { viewModel.custType.value = keyword },
                            label = { Text(display, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFBBF24),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF0F172A),
                                labelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TARGET SYMBOL", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0F172A))
                                .clickable {
                                    val currentIdx = items.indexOfFirst { it.symbol == selectedSymbol }
                                    val nextIdx = (currentIdx + 1) % items.size
                                    if (nextIdx >= 0 && items.isNotEmpty()) {
                                        viewModel.custSymbol.value = items[nextIdx].symbol
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedSymbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Icon(Icons.Default.ArrowDropDown, "down", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1.0f)) {
                        Text("COLOR THEME", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0F172A))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selectedTheme == "dark") Color(0xFF1E2638) else Color.Transparent)
                                    .clickable { viewModel.custTheme.value = "dark" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Dark", color = if (selectedTheme == "dark") Color.White else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selectedTheme == "light") Color(0xFFE2E8F0) else Color.Transparent)
                                    .clickable { viewModel.custTheme.value = "light" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Light", color = if (selectedTheme == "light") Color.Black else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (selectedType == "full-chart") {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TIMEFRAME", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0F172A))
                                    .clickable {
                                        val runTfs = listOf("1m", "5m", "1h", "1d")
                                        val nextIdx = (runTfs.indexOf(selectedTimeframe) + 1) % runTfs.size
                                        viewModel.custTimeframe.value = runTfs[nextIdx]
                                    }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(selectedTimeframe.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Icon(Icons.Default.ArrowDropDown, "down", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text("EMBED DEPLOYMENT CODES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
            border = BorderStroke(1.dp, Color(0xFF1E2638))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HTML IFrame Deployment Script", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(scriptEmbedTag))
                            Toast.makeText(context, "Embed script copied!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Send, "copy icon", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    text = scriptEmbedTag,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Direct REST API Render Hook", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(widgetUrl))
                            Toast.makeText(context, "Direct REST URL copied!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Send, "copy URL icon", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    text = widgetUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.LightGray,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text("WIDGET RENDERING INTERACTIVE PREVIEW", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        val matchedSymbol = items.find { it.symbol == selectedSymbol }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selectedTheme == "dark") Color(0xFF161F30) else Color(0xFFF1F5F9))
                .border(2.dp, Color(0xFFFBBF24), RoundedCornerShape(12.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (matchedSymbol == null) {
                    Text("Symbol data loading...", color = Color.Gray, fontSize = 12.sp)
                } else {
                    when (selectedType) {
                        "mini-card" -> WidgetMiniCardPreview(matchedSymbol, selectedTheme == "dark")
                        "stock-ticker" -> WidgetMarqueePreview(items, selectedTheme == "dark")
                        "full-chart" -> WidgetFullChartPreview(matchedSymbol, selectedTimeframe, selectedTheme == "dark")
                        "company-info" -> WidgetFundamentalsPreview(matchedSymbol, selectedTheme == "dark")
                        "news-feed" -> WidgetNewsPreview(matchedSymbol, allNews, selectedTheme == "dark")
                    }
                }
            }
        }
    }
}

// Visual widget replicas for the composing sandbox (100% reactive to simulated ticks!)
@Composable
fun WidgetMiniCardPreview(sym: MarketSymbolEntity, isDark: Boolean) {
    val textColor = if (isDark) Color.White else Color.Black
    val subColor = if (isDark) Color.LightGray else Color.DarkGray
    val isPos = sym.change >= 0
    val trendColor = if (isPos) Color(0xFF10B981) else Color(0xFFEF4444)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NASDAQ WIDGET RENDER", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(sym.symbol, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(sym.name, color = textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "$${sym.price.toLocaleString()}",
                color = textColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Text(
                text = (if (isPos) "+" else "") + "${sym.change} (${sym.percentChange}%)",
                color = trendColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(10)).background(Color(0xFF10B981)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("WS Pipeline Active", color = Color(0xFF10B981), fontSize = 9.sp)
            }
            Text("Data powered by Apex", color = subColor, fontSize = 9.sp)
        }
    }
}

@Composable
fun WidgetMarqueePreview(all: List<MarketSymbolEntity>, isDark: Boolean) {
    val textColor = if (isDark) Color.White else Color.Black
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        while (true) {
            scrollState.animateScrollTo(
                value = (scrollState.value + 3) % (scrollState.maxValue.coerceAtLeast(1) + 1),
                animationSpec = tween(durationMillis = 30)
            )
            delay(30)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("SCROLLING WIDGET BAR PREVIEW", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState, enabled = false)
                .background(if (isDark) Color(0xFF0F172A) else Color(0xFFE2E8F0))
                .padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            val combinedList = all + all + all
            combinedList.forEach { item ->
                val isPos = item.change >= 0
                val col = if (isPos) Color(0xFF10B981) else Color(0xFFEF4444)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.symbol, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("$${item.price}", color = textColor, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text((if (isPos) "+" else "") + "${item.percentChange}%", color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun WidgetFullChartPreview(sym: MarketSymbolEntity, tf: String, isDark: Boolean) {
    val textColor = if (isDark) Color.White else Color.Black
    val db = MarketDatabase.getDatabase(LocalContext.current)
    val candles = remember { mutableStateListOf<HistoricalCandleEntity>() }

    LaunchedEffect(sym.symbol, tf, sym.price) {
        val loaded = db.marketDao().getCandles(sym.symbol, tf)
        candles.clear()
        candles.addAll(loaded)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("${sym.symbol} CANDLESTICK METRICS", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Timeframe: ${tf.uppercase()}", color = Color.Gray, fontSize = 10.sp)
            }
            Text("$${sym.price}", color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(14.dp))
        CandlestickChart(
            candles = candles,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isDark) Color(0xFF0F172A) else Color.White)
                .border(1.dp, Color.Gray.copy(alpha = 0.3f))
                .padding(horizontal = 8.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun WidgetFundamentalsPreview(sym: MarketSymbolEntity, isDark: Boolean) {
    val textColor = if (isDark) Color.White else Color.Black
    val labelColor = Color.LightGray

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("COMPANY FUNDAMENTALS PROFILE WIDGET", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(sym.name, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sector", color = labelColor, fontSize = 10.sp)
                Text(sym.sector, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Market Cap", color = labelColor, fontSize = 10.sp)
                Text("$${(sym.marketCap / 1e9).toFixed(1)}B", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("P/E Ratio", color = labelColor, fontSize = 10.sp)
                Text(if (sym.peRatio == 0.0) "N/A" else sym.peRatio.toString(), color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Dividend Yield", color = labelColor, fontSize = 10.sp)
                Text("${sym.dividendYield}%", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun WidgetNewsPreview(sym: MarketSymbolEntity, news: List<FinancialNewsEntity>, isDark: Boolean) {
    val textColor = if (isDark) Color.White else Color.Black
    val filteredNews = news.filter { it.tickerTag == sym.symbol || it.tickerTag == "market-wide" }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("FINANCIAL SENTIMENT FEED", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        if (filteredNews.isEmpty()) {
            Text("No news events available.", color = Color.Gray, fontSize = 11.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredNews.take(2).forEach { n ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.1f))
                            .padding(8.dp)
                    ) {
                        Text(n.title, color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.summary, color = Color.Gray, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n.source, color = Color(0xFFFBBF24), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        when (n.sentiment) {
                                            "POSITIVE" -> Color(0x3310B981)
                                            "NEGATIVE" -> Color(0x33EF4444)
                                            else -> Color(0x3394A3B8)
                                        }
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = n.sentiment,
                                    color = when (n.sentiment) {
                                        "POSITIVE" -> Color(0xFF10B981)
                                        "NEGATIVE" -> Color(0xFFEF4444)
                                        else -> Color.Gray
                                    },
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// THE PUBLISHER TAB (CRUD EVENTS PANEL)
// ==========================================
@Composable
fun PublisherDeskTab(viewModel: MarketViewModel) {
    var tickerSym by remember { mutableStateOf("") }
    var tickerName by remember { mutableStateOf("") }
    var tickerSec by remember { mutableStateOf("") }
    var tickerPrice by remember { mutableStateOf("") }
    var tickerExchange by remember { mutableStateOf("NASDAQ") }
    var tickerCap by remember { mutableStateOf("") }
    var tickerPe by remember { mutableStateOf("") }
    var tickerYield by remember { mutableStateOf("") }
    var tickerType by remember { mutableStateOf("equity") }

    var newsTitle by remember { mutableStateOf("") }
    var newsSummary by remember { mutableStateOf("") }
    var newsSource by remember { mutableStateOf("") }
    var newsSentiment by remember { mutableStateOf("POSITIVE") }
    var newsRelevance by remember { mutableStateOf("") }
    var newsTickerTag by remember { mutableStateOf("") }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Ticker management form
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2638)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, "add assets", tint = Color.LightGray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Register Custom Symbol Asset", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                }
                Text("Inject fully custom indices, cryptos, or equities into the persistent database. They'll start receiving real-time ticks and showing in REST/WSS JSON nodes.", color = Color.LightGray, fontSize = 11.sp)

                OutlinedTextField(
                    value = tickerSym,
                    onValueChange = { tickerSym = it },
                    label = { Text("Asset Ticker Symbol (e.g. NVDA, AMZN)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFBBF24),
                        unfocusedBorderColor = Color.LightGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tickerName,
                    onValueChange = { tickerName = it },
                    label = { Text("Financial Company Full Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFBBF24),
                        unfocusedBorderColor = Color.LightGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tickerSec,
                        onValueChange = { tickerSec = it },
                        label = { Text("Sector") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tickerPrice,
                        onValueChange = { tickerPrice = it },
                        label = { Text("Starting Price ($)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tickerCap,
                        onValueChange = { tickerCap = it },
                        label = { Text("Market Capitalization ($)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tickerPe,
                        onValueChange = { tickerPe = it },
                        label = { Text("P/E Ratio") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tickerExchange,
                        onValueChange = { tickerExchange = it },
                        label = { Text("Exchange Code") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tickerYield,
                        onValueChange = { tickerYield = it },
                        label = { Text("Div Yield (%)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("ASSET CATEGORY TYPE", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val catTypes = listOf("equity", "crypto", "index", "etf")
                    catTypes.forEach { cat ->
                        FilterChip(
                            selected = tickerType == cat,
                            onClick = { tickerType = cat },
                            label = { Text(cat.uppercase(), fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFBBF24),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF0F172A),
                                labelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }

                Button(
                    onClick = {
                        val sym = tickerSym.trim()
                        val name = tickerName.trim()
                        val sec = tickerSec.trim()
                        val startPr = tickerPrice.toDoubleOrNull() ?: 100.0
                        val capVal = tickerCap.toLongOrNull() ?: 500000000L
                        val peVal = tickerPe.toDoubleOrNull() ?: 15.0
                        val yieldVal = tickerYield.toDoubleOrNull() ?: 1.5

                        if (sym.isNotEmpty() && name.isNotEmpty()) {
                            viewModel.insertCustomSymbol(
                                symbol = sym,
                                name = name,
                                sector = sec,
                                price = startPr,
                                exchange = tickerExchange,
                                mcap = capVal,
                                pe = peVal,
                                yield = yieldVal,
                                type = tickerType
                            )
                            Toast.makeText(context, "Asset $sym registered to registry database successfully!", Toast.LENGTH_SHORT).show()
                            tickerSym = ""; tickerName = ""; tickerSec = ""; tickerPrice = ""; tickerCap = ""; tickerPe = ""; tickerYield = ""
                        } else {
                            Toast.makeText(context, "Please enter Symbol details", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24), contentColor = Color.Black)
                ) {
                    Text("Register Ticker Asset", fontWeight = FontWeight.Bold)
                }
            }
        }

        // News stories management form
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2638)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.List, "news icon", tint = Color.LightGray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Publish Financial News & Sentiment Story", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                }
                Text("Publish stories tagged on symbols. Previews, REST endpoints, and stream aggregations will refresh immediately to notify visual layout segments.", color = Color.LightGray, fontSize = 11.sp)

                OutlinedTextField(
                    value = newsTitle,
                    onValueChange = { newsTitle = it },
                    label = { Text("News Title Header") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFBBF24),
                        unfocusedBorderColor = Color.LightGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newsSummary,
                    onValueChange = { newsSummary = it },
                    label = { Text("Lead Paragraph Detail Summary") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFBBF24),
                        unfocusedBorderColor = Color.LightGray
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newsSource,
                        onValueChange = { newsSource = it },
                        label = { Text("Reuters, WSJ, Bloomberg") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newsTickerTag,
                        onValueChange = { newsTickerTag = it },
                        label = { Text("Tagged Ticker (e.g. AAPL, BTC)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = newsRelevance,
                    onValueChange = { newsRelevance = it },
                    label = { Text("Relevance Score (e.g. 0.95)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFBBF24),
                        unfocusedBorderColor = Color.LightGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("MARKET SENTIMENT SIGNAL", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sentimentRanks = listOf("POSITIVE", "NEUTRAL", "NEGATIVE")
                    sentimentRanks.forEach { s ->
                        FilterChip(
                            selected = newsSentiment == s,
                            onClick = { newsSentiment = s },
                            label = { Text(s, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = when (s) {
                                    "POSITIVE" -> Color(0xFF10B981)
                                    "NEGATIVE" -> Color(0xFFEF4444)
                                    else -> Color.Gray
                                },
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF0F172A),
                                labelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }

                Button(
                    onClick = {
                        val t = newsTitle.trim()
                        val sum = newsSummary.trim()
                        val src = if (newsSource.isEmpty()) "Apex Platform" else newsSource.trim()
                        val tag = if (newsTickerTag.isEmpty()) "market-wide" else newsTickerTag.trim()
                        val relScore = newsRelevance.toDoubleOrNull() ?: 0.5

                        if (t.isNotEmpty() && sum.isNotEmpty()) {
                            viewModel.insertCustomNewsStory(
                                title = t,
                                summary = sum,
                                source = src,
                                sentiment = newsSentiment,
                                relevanceScore = relScore,
                                ticker = tag
                            )
                            Toast.makeText(context, "News Story published live successfully!", Toast.LENGTH_SHORT).show()
                            newsTitle = ""; newsSummary = ""; newsSource = ""; newsTickerTag = ""; newsRelevance = ""
                        } else {
                            Toast.makeText(context, "Please enter News title and summary details", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24), contentColor = Color.Black)
                ) {
                    Text("Publish Headline Alert", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// SHARED GENERAL COMPOSABLES
// ==========================================

@Composable
fun MetricCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, title, tint = color, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(title, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Normal)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BenchmarkItem(sym: MarketSymbolEntity, onSelect: () -> Unit) {
    val isPos = sym.change >= 0
    Card(
        modifier = Modifier
            .width(135.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2638)),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF2E3B52))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(sym.symbol, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 11.sp)
            Text(sym.name, color = Color.Gray, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text("$${sym.price.toLocaleString()}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            val trendSign = if (isPos) "+" else ""
            Text(
                text = "$trendSign${sym.percentChange}%",
                color = if (isPos) Color(0xFF10B981) else Color(0xFFEF4444),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SymbolListItem(symbol: MarketSymbolEntity, onSelect: () -> Unit) {
    val isPos = symbol.change >= 0
    val trendColor = if (isPos) Color(0xFF10B981) else Color(0xFFEF4444)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF2E3B52))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (symbol.type == "crypto") Color(0x22F59E0B) else Color(0x223B82F6)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (symbol.type == "crypto") Icons.Default.Star else Icons.Default.Home,
                        contentDescription = "Asset indicator",
                        tint = if (symbol.type == "crypto") Color(0xFFF59E0B) else Color(0xFF3B82F6),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(symbol.symbol, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text(symbol.name, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${symbol.price.toLocaleString()}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                val sign = if (isPos) "+" else ""
                Text(
                    text = "$sign${symbol.change} ($sign${symbol.percentChange}%)",
                    color = trendColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun DetailRow(label: String, valItem: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(valItem, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun ApiLogLine(log: ApiRequestLog) {
    val methodColor = when (log.method) {
        "GET" -> Color(0xFF60A5FA)
        "POST" -> Color(0xFF34D399)
        "OPTIONS" -> Color.Gray
        else -> Color(0xFFFBBF24) // WebSockets / Handshakes
    }
    val statusColor = when (log.status) {
        101, 200, 201, 204 -> Color(0xFF34D399)
        else -> Color(0xFFEF4444)
    }

    val timeStr = remember(log.timestamp) {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        format.format(Date(log.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeStr, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(methodColor.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(log.method, color = methodColor, fontWeight = FontWeight.Bold, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = log.path,
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(log.status.toString(), color = statusColor, fontWeight = FontWeight.Bold, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("${log.durationMs}ms", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// Candlestick custom canvas rendering
@Composable
fun CandlestickChart(candles: List<HistoricalCandleEntity>, modifier: Modifier = Modifier) {
    if (candles.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Awaiting Candle Samples...", color = Color.Gray, fontSize = 11.sp)
        }
        return
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val lowVal = candles.minOf { it.low }
        val highVal = candles.maxOf { it.high }
        val priceRange = (highVal - lowVal).coerceAtLeast(0.01)

        val minPrice = lowVal - priceRange * 0.05
        val maxPrice = highVal + priceRange * 0.05
        val finalRange = maxPrice - minPrice

        val candleCount = candles.size
        val candleWidth = width / candleCount

        // 1. Draw horizontal background guidelines
        val guideLinesCount = 3
        for (i in 1..guideLinesCount) {
            val y = (height / (guideLinesCount + 1)) * i
            drawLine(
                color = Color(0xFF2E3B52),
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(width, y),
                strokeWidth = 1f
            )
        }

        // 2. Draw candlestick pillars
        candles.forEachIndexed { idx, c ->
            val isGreen = c.close >= c.open
            val color = if (isGreen) Color(0xFF10B981) else Color(0xFFEF4444)

            val x = idx * candleWidth + candleWidth / 2f

            val openY = height - (((c.open - minPrice) / finalRange) * height).toFloat()
            val closeY = height - (((c.close - minPrice) / finalRange) * height).toFloat()
            val highY = height - (((c.high - minPrice) / finalRange) * height).toFloat()
            val lowY = height - (((c.low - minPrice) / finalRange) * height).toFloat()

            // Draw Wick
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, highY),
                end = androidx.compose.ui.geometry.Offset(x, lowY),
                strokeWidth = 2.5f
            )

            // Draw Body
            val topBodyY = minOf(openY, closeY)
            val bottomBodyY = maxOf(openY, closeY)
            val bodyHeight = (bottomBodyY - topBodyY).coerceAtLeast(4f)
            val bodyWidth = candleWidth * 0.70f

            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x - bodyWidth / 2f, topBodyY),
                size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
            )
        }
    }
}

// Extends Double to formatted strings representing currencies
private fun Double.toLocaleString(minFrac: Int = 2, maxFrac: Int = 2): String {
    return String.format(Locale.getDefault(), "%,.${maxFrac}f", this)
}

private fun Double.toFixed(digits: Int): String {
    return String.format(Locale.getDefault(), "%.${digits}f", this)
}

private fun Long.toLocaleString(): String {
    return String.format(Locale.getDefault(), "%,d", this)
}
