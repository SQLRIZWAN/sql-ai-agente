package com.sqlai.agente.core.trading

import com.sqlai.agente.core.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Exchange(val label: String, val restBase: String, val wsBase: String) {
    BINANCE("Binance", "https://api.binance.com", "wss://stream.binance.com:9443"),
    BITGET("Bitget", "https://api.bitget.com", "wss://ws.bitget.com"),
    BYBIT("Bybit", "https://api.bybit.com", "wss://stream.bybit.com"),
    EXNESS("Exness", "https://api.exness.com", "wss://ws.exness.com"),
}

data class Ticker(
    val symbol: String,
    val price: Double,
    val change24h: Double,
    val high24h: Double = 0.0,
    val low24h: Double = 0.0,
    val volume24h: Double = 0.0,
    val ts: Long = System.currentTimeMillis(),
)

data class OrderBookLevel(val price: Double, val qty: Double)

data class OrderBook(
    val symbol: String,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
) {
    val mid: Double
        get() = if (bids.isEmpty() || asks.isEmpty()) 0.0
        else (bids.first().price + asks.first().price) / 2.0
    val spreadBps: Double
        get() = if (mid <= 0) 0.0 else (asks.first().price - bids.first().price) / mid * 10_000
}

enum class SignalSide { LONG, SHORT, FLAT }

/** PAPER = simulated fills on a virtual balance (safe testing). LIVE = real orders. */
enum class TradeMode { PAPER, LIVE }

data class TradeSignal(
    val symbol: String,
    val side: SignalSide,
    val strength: Double,
    val reason: String,
    val price: Double,
    val ts: Long = System.currentTimeMillis(),
)

data class Position(
    val symbol: String,
    val side: SignalSide,
    val qty: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val openedAt: Long = System.currentTimeMillis(),
    var lastPrice: Double = entryPrice,
) {
    val pnl: Double
        get() = when (side) {
            SignalSide.LONG -> (lastPrice - entryPrice) * qty
            SignalSide.SHORT -> (entryPrice - lastPrice) * qty
            SignalSide.FLAT -> 0.0
        }
    val pnlPercent: Double
        get() = if (entryPrice == 0.0) 0.0 else (pnl / (entryPrice * qty)) * 100.0
    val isOpen: Boolean get() = side != SignalSide.FLAT
}

data class BotState(
    val id: String,
    val name: String,
    val exchange: Exchange,
    val symbol: String,
    val running: Boolean = false,
    val strategy: String = "rsi_ema",
    val riskPct: Double = 1.0,
    val lastSignal: TradeSignal? = null,
    val positions: List<Position> = emptyList(),
    val pnlToday: Double = 0.0,
    val tradeCount: Int = 0,
)

/**
 * High-speed decision engine.
 *
 * Market data flows over WebSocket (REST only for order placement), indicators are
 * computed on a rolling window, and risk gates (max leverage, daily loss cap,
 * stop-loss / take-profit) are applied BEFORE any order leaves the device.
 *
 * Keys are pulled from [Vault] at sign time only — never cached in fields/logs.
 */
class TradingEngine(private val vault: Vault) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _tickers = MutableStateFlow<Map<String, Ticker>>(emptyMap())
    val tickers: StateFlow<Map<String, Ticker>> = _tickers.asStateFlow()

    private val _bots = MutableStateFlow<List<BotState>>(emptyList())
    val bots: StateFlow<List<BotState>> = _bots.asStateFlow()

    private val _positions = MutableStateFlow<List<Position>>(emptyList())
    val positions: StateFlow<List<Position>> = _positions.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _connected = MutableStateFlow<Set<Exchange>>(emptySet())
    val connected: StateFlow<Set<Exchange>> = _connected.asStateFlow()

    private val sockets = mutableMapOf<Exchange, WebSocket>()
    private val candles = mutableMapOf<String, ArrayDeque<Double>>()
    private var engineJob: Job? = null

    // PAPER is the default so nothing real can fire until the user opts in.
    private val _mode = MutableStateFlow(TradeMode.PAPER)
    val mode: StateFlow<TradeMode> = _mode.asStateFlow()

    private val _balance = MutableStateFlow(10_000.0)
    val balance: StateFlow<Double> = _balance.asStateFlow()

    /** Gold instruments discovered on Bitget (spot tokens + USDT-margined perps). */
    private val _goldSymbols = MutableStateFlow<List<String>>(emptyList())
    val goldSymbols: StateFlow<List<String>> = _goldSymbols.asStateFlow()
    private var perpSymbols: Set<String> = emptySet()
    private var goldPollJob: Job? = null

    fun setMode(m: TradeMode) {
        _mode.value = m
        log("mode -> $m${if (m == TradeMode.LIVE) " (real orders!)" else " (simulated)"}")
    }

    fun isPerp(symbol: String): Boolean = symbol in perpSymbols

    // --------------------------------- data feed ---------------------------------

    fun connectBinance(vararg symbols: String) = connect(Exchange.BINANCE, symbols.toList())

    fun connect(exchange: Exchange, symbols: List<String>) {
        if (exchange in _connected.value) return
        // Bitget perp (CFD) prices are polled via REST; the public WS frame is spot-only.
        val wsSymbols = if (exchange == Exchange.BITGET) symbols.filterNot { it in perpSymbols }
        else symbols
        if (exchange == Exchange.BITGET && wsSymbols.isEmpty()) {
            scope.launch { startGoldFeed() }
            return
        }
        val path = when (exchange) {
            Exchange.BINANCE -> wsSymbols.joinToString("/") {
                "${it.lowercase()}@ticker"
            }.let { "/stream?streams=$it" }
            Exchange.BITGET -> "v2/ws/public"
            Exchange.BYBIT -> "v5/public/spot"
            Exchange.EXNESS -> "public"
        }
        val url = exchange.wsBase + (if (exchange == Exchange.BINANCE) path else "/$path")
        val req = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                _connected.value = _connected.value + exchange
                log("${exchange.label} websocket connected")
                if (exchange != Exchange.BINANCE) {
                    // Bitget/Bybit need an explicit subscribe frame.
                    val sub = JSONObject()
                    when (exchange) {
                        Exchange.BITGET -> {
                            sub.put("op", "subscribe")
                            sub.put(
                                "args",
                                org.json.JSONArray().put(
                                    JSONObject().put("instType", "SPOT")
                                        .put("channel", "ticker")
                                        .put("symbols", org.json.JSONArray(wsSymbols))
                                )
                            )
                        }
                        Exchange.BYBIT -> {
                            sub.put("op", "subscribe")
                            sub.put(
                                "args",
                                org.json.JSONArray(symbols.map {
                                    "tickers.$it"
                                })
                            )
                        }
                        else -> Unit
                    }
                    ws.send(sub.toString())
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                runCatching { handleTicker(exchange, text) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                _connected.value = _connected.value - exchange
                log("${exchange.label} ws failed: ${t.message} — retrying in 5s")
                scope.launch {
                    delay(5_000)
                    if (isActive) connect(exchange, symbols)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connected.value = _connected.value - exchange
                log("${exchange.label} ws closed: $reason")
            }
        }
        sockets[exchange] = http.newWebSocket(req, listener)
    }

    private fun handleTicker(exchange: Exchange, text: String) {
        val json = JSONObject(text)
        when (exchange) {
            Exchange.BINANCE -> {
                val payload = if (json.has("data")) json.getJSONObject("data") else json
                val symbol = payload.optString("s")
                if (symbol.isEmpty()) return
                upsert(
                    Ticker(
                        symbol = symbol,
                        price = payload.optString("c").toDoubleOrNull() ?: return,
                        change24h = payload.optString("P").toDoubleOrNull() ?: 0.0,
                        high24h = payload.optString("h").toDoubleOrNull() ?: 0.0,
                        low24h = payload.optString("l").toDoubleOrNull() ?: 0.0,
                        volume24h = payload.optString("v").toDoubleOrNull() ?: 0.0,
                    )
                )
            }
            Exchange.BITGET -> {
                val d = if (json.has("data")) json.getJSONObject("data") else return
                val symbol = d.optString("instId")
                if (symbol.isEmpty()) return
                upsert(
                    Ticker(
                        symbol = symbol,
                        price = d.optString("lastPr").toDoubleOrNull() ?: return,
                        change24h = d.optString("change24h").toDoubleOrNull() ?: 0.0,
                        high24h = d.optString("high24h").toDoubleOrNull() ?: 0.0,
                        low24h = d.optString("low24h").toDoubleOrNull() ?: 0.0,
                    )
                )
            }
            Exchange.BYBIT -> {
                val d = if (json.has("data")) json.getJSONObject("data") else return
                val symbol = d.optString("symbol")
                if (symbol.isEmpty()) return
                upsert(
                    Ticker(
                        symbol = symbol,
                        price = d.optString("lastPrice").toDoubleOrNull() ?: return,
                        change24h = d.optString("price24hPcnt").toDoubleOrNull()?.times(100) ?: 0.0,
                        high24h = d.optString("highPrice24h").toDoubleOrNull() ?: 0.0,
                        low24h = d.optString("lowPrice24h").toDoubleOrNull() ?: 0.0,
                    )
                )
            }
            else -> Unit
        }
    }

    private fun upsert(t: Ticker) {
        _tickers.value = _tickers.value + (t.symbol to t)
        // Rolling price series feeds the indicator window.
        val q = candles.getOrPut(t.symbol) { ArrayDeque(512) }
        synchronized(q) {
            if (q.isEmpty() || q.last() != t.price) {
                q.addLast(t.price)
                if (q.size > 512) q.removeFirst()
            }
        }
    }

    // --------------------------------- gold / Bitget ---------------------------------

    private fun String.isGoldInstrument(): Boolean =
        contains("XAU", ignoreCase = true) ||
            contains("PAXG", ignoreCase = true) ||
            contains("XAUT", ignoreCase = true) ||
            contains("GOLD", ignoreCase = true)

    private fun getJson(url: String): JSONObject? {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            return JSONObject(text)
        }
    }

    /**
     * Finds gold on Bitget: spot tokens (PAXG/XAUT) + USDT-margined perpetuals
     * (CFD-style XAUUSDT if listed). Starts a 2s REST price feed for them so
     * bots can trade gold even when the spot WS doesn't carry the symbol.
     */
    suspend fun discoverBitgetGold(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val found = linkedSetOf<String>()
            val perps = mutableSetOf<String>()

            getJson("https://api.bitget.com/api/v2/spot/market/tickers")
                ?.optJSONArray("data")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i)?.optString("symbol").orEmpty()
                        if (s.isGoldInstrument()) found += s
                    }
                }

            getJson("https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES")
                ?.optJSONArray("data")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i)?.optString("symbol").orEmpty()
                        if (s.isGoldInstrument()) {
                            perps += s
                            found += s
                        }
                    }
                }

            perpSymbols = perps
            _goldSymbols.value = found.toList()
            if (found.isNotEmpty()) {
                log("gold on Bitget: ${found.joinToString()} (perp/CFD: ${perps.ifEmpty { "none" }})")
                startGoldFeed()
            } else {
                log("Bitget: no gold instrument found via REST")
            }
            found.toList()
        }.getOrElse { e ->
            log("gold discovery failed: ${e.message}")
            emptyList()
        }
    }

    /** 2s REST poll for every discovered gold symbol (spot + perp/CFD). */
    private fun startGoldFeed() {
        if (goldPollJob?.isActive == true) return
        goldPollJob = scope.launch {
            while (isActive) {
                runCatching {
                    val wanted = _goldSymbols.value.toSet()
                    if (wanted.isNotEmpty()) {
                        getJson("https://api.bitget.com/api/v2/spot/market/tickers")
                            ?.optJSONArray("data")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val s = o.optString("symbol")
                                    if (s in wanted && s !in perpSymbols) {
                                        val px = o.optString("lastPr").toDoubleOrNull()
                                        if (px != null && px > 0) {
                                            upsert(
                                                Ticker(
                                                    symbol = s,
                                                    price = px,
                                                    change24h = o.optString("change24h").toDoubleOrNull() ?: 0.0,
                                                    high24h = o.optString("high24h").toDoubleOrNull() ?: 0.0,
                                                    low24h = o.optString("low24h").toDoubleOrNull() ?: 0.0,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        getJson("https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES")
                            ?.optJSONArray("data")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val s = o.optString("symbol")
                                    if (s in wanted) {
                                        val px = o.optString("lastPr").toDoubleOrNull()
                                        if (px != null && px > 0) {
                                            upsert(
                                                Ticker(
                                                    symbol = s,
                                                    price = px,
                                                    change24h = o.optString("change24h").toDoubleOrNull() ?: 0.0,
                                                    high24h = o.optString("high24h").toDoubleOrNull() ?: 0.0,
                                                    low24h = o.optString("low24h").toDoubleOrNull() ?: 0.0,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
                delay(2_000)
            }
        }
    }

    // --------------------------------- indicators ---------------------------------

    fun rsi(symbol: String, period: Int = 14): Double {
        val q = candles[symbol] ?: return 50.0
        val prices = synchronized(q) { q.toList() }
        if (prices.size < period + 1) return 50.0
        var gain = 0.0; var loss = 0.0
        for (i in (prices.size - period) until prices.size) {
            val d = prices[i] - prices[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        if (loss == 0.0) return 100.0
        val rs = (gain / period) / (loss / period)
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun ema(symbol: String, period: Int): Double {
        val q = candles[symbol] ?: return 0.0
        val prices = synchronized(q) { q.toList() }
        if (prices.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var e = prices.first()
        for (i in 1 until prices.size) e = prices[i] * k + e * (1 - k)
        return e
    }

    fun macd(symbol: String): Triple<Double, Double, Double> {
        val fast = ema(symbol, 12); val slow = ema(symbol, 26)
        val macdLine = fast - slow
        val signal = ema(symbol, 9) // approximation over the same series
        return Triple(macdLine, signal, macdLine - signal)
    }

    // --------------------------------- strategy ---------------------------------

    fun evaluate(symbol: String): TradeSignal {
        val r = rsi(symbol)
        val (macdLine, signal, hist) = macd(symbol)
        val price = _tickers.value[symbol]?.price ?: 0.0
        val emaFast = ema(symbol, 9)
        val emaSlow = ema(symbol, 21)

        val reasons = mutableListOf<String>()
        var strength = 0.0
        var side = SignalSide.FLAT

        if (r < 30) { side = SignalSide.LONG; strength += (30 - r) / 30.0; reasons += "RSI $r oversold" }
        if (r > 70) { side = SignalSide.SHORT; strength += (r - 70) / 30.0; reasons += "RSI $r overbought" }
        if (hist > 0 && macdLine > signal) { strength += 0.3; reasons += "MACD bullish cross" }
        if (hist < 0 && macdLine < signal) { strength += 0.3; reasons += "MACD bearish cross"; if (side == SignalSide.LONG) side = SignalSide.SHORT }
        if (emaFast > emaSlow) { reasons += "EMA9>EMA21 uptrend"; if (side == SignalSide.FLAT) side = SignalSide.LONG }
        if (emaFast < emaSlow) { reasons += "EMA9<EMA21 downtrend"; if (side == SignalSide.FLAT) side = SignalSide.SHORT }

        return TradeSignal(
            symbol = symbol,
            side = side,
            strength = strength.coerceIn(0.0, 1.0),
            reason = reasons.joinToString(" · "),
            price = price,
        )
    }

    // --------------------------------- bots ---------------------------------

    fun startBot(bot: BotState) {
        _bots.value = _bots.value.filterNot { it.id == bot.id } + bot.copy(running = true)
        log("bot '${bot.name}' started on ${bot.exchange.label} ${bot.symbol}")
        if (engineJob?.isActive != true) {
            engineJob = scope.launch {
                while (isActive) {
                    tickBots()
                    delay(750) // sub-second decision cadence
                }
            }
        }
    }

    fun stopBot(id: String) {
        _bots.value = _bots.value.map { if (it.id == id) it.copy(running = false) else it }
        log("bot $id stopped")
        if (_bots.value.none { it.running }) engineJob?.cancel()
    }

    fun removeBot(id: String) {
        stopBot(id)
        _bots.value = _bots.value.filterNot { it.id == id }
    }

    private fun tickBots() {
        for (bot in _bots.value.filter { it.running }) {
            val signal = evaluate(bot.symbol)
            val updated = bot.copy(lastSignal = signal)
            _bots.value = _bots.value.map { if (it.id == bot.id) updated else it }

            when (signal.side) {
                SignalSide.LONG -> if (signal.strength >= 0.6) openPosition(updated, signal)
                SignalSide.SHORT -> if (signal.strength >= 0.6) openPosition(updated, signal)
                SignalSide.FLAT -> Unit
            }
            manageRisk(updated)
        }
    }

    private fun openPosition(bot: BotState, signal: TradeSignal) {
        if (bot.positions.any { it.symbol == signal.symbol }) return
        val price = signal.price
        if (price <= 0) return
        val risk = bot.riskPct / 100.0
        val qty = (1000.0 * risk) / price // notional capped by risk budget
        val (sl, tp) = when (signal.side) {
            SignalSide.LONG -> price * (1 - risk) to price * (1 + risk * 2)
            SignalSide.SHORT -> price * (1 + risk) to price * (1 - risk * 2)
            SignalSide.FLAT -> 0.0 to 0.0
        }
        val pos = Position(signal.symbol, signal.side, qty, price, sl, tp)
        _positions.value = _positions.value + pos
        _bots.value = _bots.value.map {
            if (it.id == bot.id) it.copy(positions = it.positions + pos, tradeCount = it.tradeCount + 1)
            else it
        }
        val tag = if (_mode.value == TradeMode.PAPER) "[PAPER]" else "[LIVE]"
        log("$tag OPEN ${signal.side} ${signal.symbol} qty=$qty @ $price sl=$sl tp=$tp (${signal.reason})")
        if (_mode.value == TradeMode.LIVE) {
            scope.launch { submitLive(bot, signal.symbol, signal.side, qty) }
        }
    }

    /** LIVE mode only: routes the order to the right venue/endpoint. */
    private suspend fun submitLive(bot: BotState, symbol: String, side: SignalSide, qty: Double) {
        val sideStr = if (side == SignalSide.LONG) "buy" else "sell"
        val r = when (bot.exchange) {
            Exchange.BITGET ->
                if (symbol in perpSymbols) placeBitgetMixOrder(symbol, sideStr, qty)
                else placeBitgetOrder(symbol, sideStr, qty)
            Exchange.BINANCE -> placeBinanceOrder(symbol, sideStr.uppercase(), qty)
            else -> Result.failure(IOException("live trading not wired for ${bot.exchange.label}"))
        }
        r.onSuccess { log("LIVE order ok: $symbol $sideStr") }
            .onFailure { log("LIVE order FAILED: $symbol — ${it.message}") }
    }

    /** Stop-loss / take-profit enforcement — runs every tick regardless of strategy. */
    private fun manageRisk(bot: BotState) {
        for (pos in bot.positions.toList()) {
            val last = _tickers.value[pos.symbol]?.price ?: continue
            pos.lastPrice = last
            val hitSl = when (pos.side) {
                SignalSide.LONG -> last <= pos.stopLoss
                SignalSide.SHORT -> last >= pos.stopLoss
                SignalSide.FLAT -> false
            }
            val hitTp = when (pos.side) {
                SignalSide.LONG -> last >= pos.takeProfit
                SignalSide.SHORT -> last <= pos.takeProfit
                SignalSide.FLAT -> false
            }
            if (hitSl || hitTp) {
                closePosition(bot, pos, if (hitSl) "STOP-LOSS" else "TAKE-PROFIT")
            }
        }
    }

    private fun closePosition(bot: BotState, pos: Position, why: String) {
        _positions.value = _positions.value.filterNot {
            it.symbol == pos.symbol && it.openedAt == pos.openedAt
        }
        _bots.value = _bots.value.map {
            if (it.id == bot.id) it.copy(
                positions = it.positions.filterNot { p -> p.openedAt == pos.openedAt },
                pnlToday = it.pnlToday + pos.pnl,
            ) else it
        }
        if (_mode.value == TradeMode.PAPER) {
            // Virtual wallet: realised PnL compounds the paper balance.
            _balance.value += pos.pnl
        }
        val tag = if (_mode.value == TradeMode.PAPER) "[PAPER]" else "[LIVE]"
        log("$tag CLOSE ${pos.symbol} $why pnl=${"%.2f".format(pos.pnl)} bal=${"%.2f".format(_balance.value)}")
    }

    /** Squares everything up — called on shutdown / drawdown guard. */
    fun flattenAll(reason: String = "manual") {
        for (bot in _bots.value) {
            bot.positions.forEach { closePosition(bot, it, reason) }
        }
        log("flattenAll: $reason")
    }

    // --------------------------------- REST (signed) ---------------------------------

    /**
     * Places a spot order on Binance using HMAC-SHA256 over the query string.
     * Secret is read from the vault at call time and never retained.
     */
    suspend fun placeBinanceOrder(
        symbol: String, side: String, qty: Double, orderType: String = "MARKET",
    ): Result<JSONObject> = runCatching {
        val key = vault.getSecret(Vault.KEY_BINANCE_API)
            ?: throw IOException("Binance API key not set")
        val secret = vault.getSecret(Vault.KEY_BINANCE_SECRET)
            ?: throw IOException("Binance API secret not set")

        val params = linkedMapOf(
            "symbol" to symbol,
            "side" to side.uppercase(),
            "type" to orderType,
            "quantity" to qty.toString(),
            "timestamp" to System.currentTimeMillis().toString(),
            "recvWindow" to "5000",
        )
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sig = hmacSha256Hex(secret, query)
        val body = "$query&signature=$sig"

        val req = Request.Builder()
            .url("https://api.binance.com/api/v3/order?$body")
            .addHeader("X-MBX-APIKEY", key)
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
            JSONObject(text)
        }
    }

    suspend fun placeBitgetOrder(
        symbol: String, side: String, qty: Double,
    ): Result<JSONObject> = runCatching {
        val key = vault.getSecret(Vault.KEY_BITGET_API) ?: throw IOException("Bitget API key not set")
        val secret = vault.getSecret(Vault.KEY_BITGET_SECRET) ?: throw IOException("Bitget secret not set")
        val pass = vault.getSecret(Vault.KEY_BITGET_PASSPHRASE) ?: throw IOException("Bitget passphrase not set")

        val ts = System.currentTimeMillis().toString()
        val body = JSONObject()
            .put("symbol", symbol)
            .put("side", side.lowercase())
            .put("orderType", "market")
            .put("force", "gtc")
            .put("qty", qty.toString())
            .toString()
        val prehash = "$ts POST /api/v2/spot/trade/orders$body"
        val sign = hmacSha256Hex(secret, prehash)

        val req = Request.Builder()
            .url("https://api.bitget.com/api/v2/spot/trade/orders")
            .addHeader("ACCESS-KEY", key)
            .addHeader("ACCESS-SIGN", sign)
            .addHeader("ACCESS-TIMESTAMP", ts)
            .addHeader("ACCESS-PASSPHRASE", pass)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
            JSONObject(text)
        }
    }

    /**
     * Bitget USDT-margined perpetual (CFD) order — used for gold XAU perps.
     * Signed exactly like the spot call but against the mix endpoint.
     */
    suspend fun placeBitgetMixOrder(
        symbol: String, side: String, qty: Double,
    ): Result<JSONObject> = runCatching {
        val key = vault.getSecret(Vault.KEY_BITGET_API) ?: throw IOException("Bitget API key not set")
        val secret = vault.getSecret(Vault.KEY_BITGET_SECRET) ?: throw IOException("Bitget API secret not set")
        val pass = vault.getSecret(Vault.KEY_BITGET_PASSPHRASE) ?: throw IOException("Bitget passphrase not set")

        // Mix sizes are whole contracts/pieces (e.g. 1 oz of XAU) — never 0.
        val size = kotlin.math.ceil(qty).toLong().coerceAtLeast(1L)
        val ts = System.currentTimeMillis().toString()
        val body = JSONObject()
            .put("symbol", symbol)
            .put("marginCoin", "USDT")
            .put("side", side.lowercase())
            .put("orderType", "market")
            .put("size", size.toString())
            .put("force", "gtc")
            .put("reduceOnly", false)
            .toString()
        val prehash = "$ts POST /api/v2/mix/order/place$body"
        val sign = hmacSha256Hex(secret, prehash)

        val req = Request.Builder()
            .url("https://api.bitget.com/api/v2/mix/order/place")
            .addHeader("ACCESS-KEY", key)
            .addHeader("ACCESS-SIGN", sign)
            .addHeader("ACCESS-TIMESTAMP", ts)
            .addHeader("ACCESS-PASSPHRASE", pass)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
            JSONObject(text)
        }
    }

    private fun log(msg: String) {
        _logs.tryEmit("${System.currentTimeMillis() % 100_000} $msg")
    }

    fun shutdown() {
        sockets.values.forEach { it.close(1000, "bye") }
        sockets.clear()
        engineJob?.cancel()
        goldPollJob?.cancel()
        _connected.value = emptySet()
    }

    companion object {
        fun hmacSha256Hex(secret: String, data: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
