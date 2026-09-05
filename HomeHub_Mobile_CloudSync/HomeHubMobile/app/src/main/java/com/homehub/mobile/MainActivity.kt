package com.homehub.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

private val Bg = Color(0xFFF3F4F6)
private val Panel = Color(0xFFFFFFFF)
private val Panel2 = Color(0xFFF8FAFC)
private val Accent = Color(0xFF2563EB)
private val TextMain = Color(0xFF111827)
private val Muted = Color(0xFF6B7280)

private const val PREFS = "homehub_local"
private const val DB_KEY = "db"
private const val SYNC_KEY = "sync_url"
private const val CLOUD_URL_KEY = "cloud_url"
private const val CLOUD_KEY_KEY = "cloud_anon_key"
private const val BOOTSTRAP_KEY = "cloud_bootstrap"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeHubApp() }
    }
}

fun newDb(): String = JSONObject().apply {
    put("app", JSONObject().apply {
        put("version", "7.0.0")
        put("family_code", "")
        put("owner_id", "")
        put("current_user_id", "")
        put("settings", JSONObject().put("quiet_hours", false))
    })
    arrayOf(
        "profiles", "rooms", "tasks", "shopping", "events", "pets", "packages",
        "meals", "inventory", "announcements", "polls", "notifications", "activity",
        "trips", "goals"
    ).forEach { put(it, JSONArray()) }
    put("emergency", JSONObject().apply {
        put("contacts", JSONArray())
        put("info", "")
    })
}.toString()

fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

fun id(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.getDefault())

fun familyCode(): String = (1..6)
    .map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }
    .joinToString("")

fun profile(db: String): JSONObject? {
    val root = runCatching { JSONObject(db) }.getOrNull() ?: return null
    val profiles = root.optJSONArray("profiles") ?: return null
    val currentId = root.optJSONObject("app")?.optString("current_user_id").orEmpty()

    for (i in 0 until profiles.length()) {
        val item = profiles.optJSONObject(i)
        if (item?.optString("id") == currentId) return item
    }
    return profiles.optJSONObject(0)
}

fun arr(db: String, key: String): JSONArray = runCatching {
    JSONObject(db).optJSONArray(key) ?: JSONArray()
}.getOrElse { JSONArray() }

fun count(db: String, key: String): Int = arr(db, key).length()

fun listNames(db: String, key: String, nameKey: String = "name"): List<String> {
    val array = arr(db, key)
    return (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.optString(nameKey)?.takeIf { it.isNotBlank() }
    }
}

fun normalizeDb(raw: String): String {
    val root = runCatching { JSONObject(raw) }.getOrElse { return newDb() }
    val shopping = root.optJSONArray("shopping") ?: JSONArray()

    if (shopping.length() > 0 && shopping.optJSONObject(0)?.has("items") != true) {
        val list = JSONObject().apply {
            put("id", id())
            put("name", "Bevásárlólista")
            put("created_by", profile(raw)?.optString("name").orEmpty())
            put("created", "")
            put("items", shopping)
        }
        root.put("shopping", JSONArray().put(list))
    }

    return root.toString()
}

fun loadDb(context: Context): String = normalizeDb(
    prefs(context).getString(DB_KEY, null) ?: newDb()
)

fun saveDb(context: Context, db: String) {
    prefs(context).edit().putString(DB_KEY, normalizeDb(db)).apply()
}

fun syncUrl(context: Context): String = prefs(context).getString(SYNC_KEY, "") ?: ""

fun saveSyncUrl(context: Context, value: String) {
    prefs(context).edit().putString(SYNC_KEY, value.trim().removeSuffix("/")).apply()
}
fun cloudUrl(context: Context): String = prefs(context).getString(CLOUD_URL_KEY, "") ?: ""
fun cloudKey(context: Context): String = prefs(context).getString(CLOUD_KEY_KEY, "") ?: ""
fun saveCloudConfig(context: Context, url: String, key: String) {
    prefs(context).edit().putString(CLOUD_URL_KEY, url.trim().removeSuffix("/")).putString(CLOUD_KEY_KEY, key.trim()).apply()
}
fun touchDb(raw: String): String = runCatching { JSONObject(raw).apply { getJSONObject("app").put("updated_at", java.time.Instant.now().toString()) }.toString() }.getOrElse { raw }


suspend fun cloudRpc(base: String, anonKey: String, function: String, body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
    try {
        val url = URL("${base.trim().removeSuffix("/")}/rest/v1/rpc/$function")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 7000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("apikey", anonKey); c.setRequestProperty("Authorization", "Bearer $anonKey"); c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""; c.disconnect()
        if (code !in 200..299) Result.failure(Exception("Cloud HTTP $code: $text")) else Result.success(JSONObject(text))
    } catch (e: Exception) { Result.failure(e) }
}

suspend fun cloudSync(context: Context, db: String): Result<String> {
    val base = cloudUrl(context); val key = cloudKey(context)
    if (base.isBlank() || key.isBlank()) return Result.failure(Exception("Nincs HomeHub Cloud beállítva."))
    val root = JSONObject(db); val code = root.optJSONObject("app")?.optString("family_code").orEmpty()
    if (code.isBlank()) return Result.failure(Exception("Nincs családi kód."))
    val bootstrap = prefs(context).getString(BOOTSTRAP_KEY, "").orEmpty()
    if (bootstrap == "join") {
        val pulled = cloudRpc(base,key,"homehub_get",JSONObject().put("p_family_code",code)).getOrElse { return Result.failure(it) }
        val server = pulled.optJSONObject("state") ?: return Result.failure(Exception("A család nem található a felhőben."))
        val localProfile = root.optJSONArray("profiles")?.optJSONObject(0)
        val profiles = server.optJSONArray("profiles") ?: JSONArray()
        if (localProfile != null && (0 until profiles.length()).none { profiles.optJSONObject(it)?.optString("id") == localProfile.optString("id") }) profiles.put(localProfile)
        server.put("profiles", profiles); server.optJSONObject("app")?.put("current_user_id", localProfile?.optString("id").orEmpty()); server.optJSONObject("app")?.put("updated_at", java.time.Instant.now().toString())
        val saved = cloudRpc(base,key,"homehub_save",JSONObject().put("p_family_code",code).put("p_state",server)).getOrElse { return Result.failure(it) }
        prefs(context).edit().remove(BOOTSTRAP_KEY).apply()
        return Result.success(saved.optJSONObject("state")?.toString() ?: server.toString())
    }
    val sent = cloudRpc(base,key,"homehub_save",JSONObject().put("p_family_code",code).put("p_state",root)).getOrElse { return Result.failure(it) }
    prefs(context).edit().remove(BOOTSTRAP_KEY).apply()
    return Result.success(sent.optJSONObject("state")?.toString() ?: db)
}

suspend fun httpState(
    base: String,
    familyCode: String,
    postBody: String? = null
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val clean = base.trim().removeSuffix("/")
        val connection = URL("$clean/state").openConnection() as HttpURLConnection
        connection.connectTimeout = 3500
        connection.readTimeout = 5000
        connection.setRequestProperty("X-Family-Code", familyCode)
        connection.setRequestProperty("Accept", "application/json")

        if (postBody != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()

        if (responseCode !in 200..299) {
            Result.failure(Exception("HTTP $responseCode: $body"))
        } else {
            val root = JSONObject(body)
            Result.success(root.optJSONObject("state")?.toString() ?: body)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@Composable
fun HomeHubApp() {
    val context = LocalContext.current
    var db by remember { mutableStateOf(loadDb(context)) }
    var page by remember { mutableStateOf("Kezdőlap") }

    val hasFamily = remember(db) {
        arr(db, "profiles").length() > 0 &&
            JSONObject(db).optJSONObject("app")?.optString("family_code").orEmpty().isNotBlank()
    }

    LaunchedEffect(hasFamily) {
        if (hasFamily) {
            while (true) {
                cloudSync(context, loadDb(context)).onSuccess { remote -> val normalized = normalizeDb(remote); saveDb(context, normalized); db = normalized }
                kotlinx.coroutines.delay(5000)
            }
        }
    }
    if (!hasFamily) {
        Onboarding { newDbString -> val normalized = touchDb(newDbString); saveDb(context, normalized); db = normalized }
    } else {
        MainShell(db = db, page = page, onPage = { page = it }, onDb = { newDb -> val normalized = touchDb(newDb); saveDb(context, normalized); db = normalized })
    }
}

@Composable
fun Onboarding(onDone: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var join by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.padding(20.dp).widthIn(max = 540.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("HOMEHUB", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Az otthonod központja.", color = TextMain, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Offline-first. A telefon és tablet önállóan is működik, a PC-vel pedig helyi Wi-Fi-n szinkronizálható.",
                    color = Muted
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Név") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = birth,
                    onValueChange = { birth = it },
                    label = { Text("Születési dátum • ÉÉÉÉ-HH-NN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (join) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Családi kód") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    enabled = name.isNotBlank() && (!join || code.isNotBlank()),
                    onClick = {
                        val pid = id()
                        val family = if (join) code.trim().uppercase() else familyCode()
                        val root = JSONObject(newDb())
                        root.getJSONObject("app").apply {
                            put("family_code", family)
                            put("current_user_id", pid)
                            put("updated_at", java.time.Instant.now().toString())
                            if (!join) put("owner_id", pid)
                        }
                        root.getJSONArray("profiles").put(JSONObject().apply {
                            put("id", pid)
                            put("name", name.trim())
                            put("birth", birth.trim())
                            put("role", if (join) "MEMBER" else "OWNER")
                            put("avatar", if (join) "🙂" else "👑")
                            put("color", if (join) "#2563EB" else "#16A34A")
                        })
                        prefs(context).edit().putString(BOOTSTRAP_KEY, if (join) "join" else "create").apply()
                        onDone(root.toString())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text(if (join) "CSATLAKOZÁS" else "ÚJ CSALÁD LÉTREHOZÁSA")
                }
                TextButton(
                    onClick = { join = !join },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (join) "Új családot hozok létre" else "Már van családi kódom")
                }
                Text(
                    if (join) {
                        "A meglévő családi adatok betöltéséhez a Beállításokban add meg a PC helyi címét, majd nyomj SZINKRONIZÁLÁS-t."
                    } else {
                        "A családi kódot később a Család oldalon is megtalálod."
                    },
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    db: String,
    page: String,
    onPage: (String) -> Unit,
    onDb: (String) -> Unit
) {
    val tablet = LocalConfiguration.current.screenWidthDp >= 700
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val tabs = listOf(
        "Kezdőlap", "Bevásárlás", "Feladatok", "Naptár", "Kisállatok", "Csomagok",
        "Ételek", "Hol van?", "Család", "Utazás", "Vészinfó", "HomeHub AI", "Beállítások"
    )

    if (!tablet) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Panel) {
                    Text(
                        "⌂ HOMEHUB",
                        color = TextMain,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(20.dp)
                    )
                    tabs.forEach { tab ->
                        NavRow(tab, page == tab) {
                            onPage(tab)
                            scope.launch { drawerState.close() }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "● OFFLINE • WI-FI SYNC",
                        color = Color(0xFF16A34A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        ) {
            Scaffold(
                containerColor = Bg,
                topBar = {
                    TopAppBar(
                        title = { Text(page, color = TextMain, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text("☰", color = TextMain, fontSize = 24.sp)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                    )
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    Page(page, db, onDb)
                }
            }
        }
    } else {
        Row(Modifier.fillMaxSize().background(Bg)) {
            Column(
                Modifier.width(220.dp).fillMaxHeight().background(Panel).padding(12.dp)
            ) {
                Text(
                    "⌂ HOMEHUB",
                    color = TextMain,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )
                profile(db)?.let {
                    Text(
                        "${it.optString("name")} • ${it.optString("role")}",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                tabs.forEach { tab -> NavRow(tab, page == tab) { onPage(tab) } }
                Spacer(Modifier.weight(1f))
                Text(
                    "● OFFLINE • WI-FI SYNC",
                    color = Color(0xFF16A34A),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                Page(page, db, onDb)
            }
        }
    }
}

@Composable
fun NavRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        "${iconFor(title)}  $title",
        color = if (selected) TextMain else Muted,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) Color(0xFFE8EEF9) else Color.Transparent,
                RoundedCornerShape(9.dp)
            )
            .padding(10.dp)
    )
}

fun iconFor(value: String): String = when (value) {
    "Kezdőlap" -> "⌂"
    "Bevásárlás" -> "🛒"
    "Feladatok" -> "✓"
    "Naptár" -> "▦"
    "Kisállatok" -> "🐾"
    "Csomagok" -> "📦"
    "Ételek" -> "🍕"
    "Hol van?" -> "⌖"
    "Család" -> "👥"
    "Utazás" -> "✈"
    "Vészinfó" -> "⚠"
    "HomeHub AI" -> "✦"
    else -> "⚙"
}

@Composable
fun Page(page: String, db: String, onDb: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "${iconFor(page)}  $page",
            color = TextMain,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (page == "HomeHub AI") "Helyi AI, internet nélkül." else "HomeHub • helyi adatok",
            color = Muted
        )

        when (page) {
            "Kezdőlap" -> HomePage(db)
            "Bevásárlás" -> ShoppingPage(db, onDb)
            "Család" -> FamilyPage(db)
            "HomeHub AI" -> AIView(db, onDb)
            "Beállítások" -> SettingsPage(db, onDb)
            "Vészinfó" -> ModulePage("emergency", db, "Vészhelyzeti adatok", "")
            else -> ModulePage(pageKey(page), db, page, description(page))
        }
    }
}

@Composable
fun HomePage(db: String) {
    ItemStatRow(db)
    CardBox(
        "Mai áttekintés",
        "${count(db, "tasks")} feladat • ${shoppingItemCount(db)} bevásárlási tétel • " +
            "${count(db, "events")} esemény • ${count(db, "packages")} csomag. Minden helyben tárolva."
    )
    val activities = listNames(db, "activity", "text")
    if (activities.isNotEmpty()) {
        CardBox("Legutóbbi tevékenység", activities.take(5).joinToString("\n"))
    }
}

fun shoppingItemCount(db: String): Int {
    val lists = arr(db, "shopping")
    var total = 0
    for (i in 0 until lists.length()) {
        total += lists.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
    }
    return total
}

@Composable
fun ItemStatRow(db: String) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Stat("Család", count(db, "profiles"))
        Stat("Bevásárlás", shoppingItemCount(db))
        Stat("Feladat", count(db, "tasks"))
        Stat("Esemény", count(db, "events"))
        Stat("Csomag", count(db, "packages"))
    }
}

@Composable
fun Stat(title: String, value: Int) {
    CardBox(title, value.toString(), Modifier.width(130.dp))
}

@Composable
fun CardBox(title: String, body: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(Panel),
        shape = RoundedCornerShape(17.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (body.isNotBlank()) {
                Text(body, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

fun pageKey(page: String): String = when (page) {
    "Bevásárlás" -> "shopping"
    "Feladatok" -> "tasks"
    "Naptár" -> "events"
    "Kisállatok" -> "pets"
    "Csomagok" -> "packages"
    "Ételek" -> "meals"
    "Hol van?" -> "inventory"
    "Utazás" -> "trips"
    else -> "announcements"
}

fun description(page: String): String = when (page) {
    "Bevásárlás" -> "Nyitott bevásárlási tételek."
    "Feladatok" -> "Feladatok és határidők."
    "Naptár" -> "Események és születésnapok."
    "Kisállatok" -> "Etetés, séta, oltás, állatorvos."
    "Csomagok" -> "Csomagok és státuszok."
    "Ételek" -> "Heti menü és hozzávalók."
    "Hol van?" -> "Háztartási tárgyak helye."
    "Utazás" -> "Utazási tervek és csomaglista."
    else -> "Helyi HomeHub adatok."
}

@Composable
fun ShoppingPage(db: String, onDb: (String) -> Unit) {
    val root = JSONObject(db)
    val lists = root.optJSONArray("shopping") ?: JSONArray()
    var createOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var itemTarget by remember { mutableStateOf(-1) }
    var itemName by remember { mutableStateOf("") }
    var itemQty by remember { mutableStateOf("1") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { createOpen = true },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("＋  BEVÁSÁRLÓLISTA LÉTREHOZÁSA")
        }

        if (lists.length() == 0) {
            CardBox("Nincs lista", "Hozz létre egy bevásárlólistát, aztán mehet bele bármi.")
        }

        for (i in 0 until lists.length()) {
            val list = lists.optJSONObject(i) ?: continue
            val items = list.optJSONArray("items") ?: JSONArray()
            val canDelete = items.length() == 0 ||
                (0 until items.length()).all { j ->
                    items.optJSONObject(j)?.optBoolean("bought", false) == true
                }

            Card(
                colors = CardDefaults.cardColors(Panel),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                list.optString("name", "Bevásárlólista"),
                                color = TextMain,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${items.length()} termék • bárki hozzáadhat",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        if (canDelete) {
                            TextButton(onClick = {
                                lists.remove(i)
                                onDb(root.toString())
                            }) {
                                Text("Lista törlése", color = Color(0xFFB91C1C))
                            }
                        }
                    }

                    Button(
                        onClick = { itemTarget = i },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E7FF))
                    ) {
                        Text("＋ Termék", color = Color(0xFF3730A3))
                    }

                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        val bought = item.optBoolean("bought", false)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .background(Panel2, RoundedCornerShape(8.dp))
                                .padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (bought) "☑" else "☐",
                                color = if (bought) Color(0xFF16A34A) else TextMain,
                                fontSize = 17.sp
                            )
                            Text(
                                "${item.optString("name")}  ×${item.optString("qty", "1")}",
                                color = if (bought) Muted else TextMain,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            TextButton(onClick = {
                                item.put("bought", !bought)
                                onDb(root.toString())
                            }) {
                                Text(if (bought) "Vissza" else "Megvan")
                            }
                            TextButton(onClick = {
                                items.remove(j)
                                onDb(root.toString())
                            }) {
                                Text("×", color = Color(0xFFB91C1C), fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("Új bevásárlólista") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Lista neve") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        lists.put(JSONObject().apply {
                            put("id", id())
                            put("name", newName.trim())
                            put("created_by", profile(db)?.optString("name").orEmpty())
                            put("created", "")
                            put("items", JSONArray())
                        })
                        onDb(root.toString())
                        newName = ""
                        createOpen = false
                    }
                }) {
                    Text("Létrehozás")
                }
            },
            dismissButton = {
                TextButton(onClick = { createOpen = false }) { Text("Mégse") }
            }
        )
    }

    if (itemTarget >= 0) {
        AlertDialog(
            onDismissRequest = { itemTarget = -1 },
            title = { Text("Termék hozzáadása") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text("Termék") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = itemQty,
                        onValueChange = { itemQty = it },
                        label = { Text("Mennyiség") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (itemName.isNotBlank()) {
                        val list = lists.optJSONObject(itemTarget)
                        list?.optJSONArray("items")?.put(JSONObject().apply {
                            put("id", id())
                            put("name", itemName.trim())
                            put("qty", itemQty.ifBlank { "1" })
                            put("bought", false)
                            put("added_by", profile(db)?.optString("name").orEmpty())
                        })
                        onDb(root.toString())
                        itemName = ""
                        itemQty = "1"
                        itemTarget = -1
                    }
                }) {
                    Text("Hozzáadás")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemTarget = -1 }) { Text("Mégse") }
            }
        )
    }
}

@Composable
fun ModulePage(key: String, db: String, title: String, descriptionText: String) {
    CardBox(title, descriptionText)
    val array = if (key == "emergency") JSONArray() else arr(db, key)
    if (key == "emergency") {
        val emergency = runCatching { JSONObject(db).optJSONObject("emergency") }.getOrNull()
        val info = emergency?.optString("info").orEmpty()
        val contacts = emergency?.optJSONArray("contacts")?.length() ?: 0
        CardBox("Vészhelyzeti információ", if (info.isBlank()) "Még nincs megadva." else info)
        CardBox("Vészhelyzeti kontaktok", "$contacts mentett kontakt")
        return
    }
    if (array.length() == 0) {
        CardBox("Üres", "Még nincs adat ebben a modulban.")
        return
    }
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val text = item.optString("name")
            .ifBlank { item.optString("title") }
            .ifBlank { item.optString("item") }
            .ifBlank { item.optString("meal") }
            .ifBlank { "Elem ${i + 1}" }
        val details = item.keys().asSequence()
            .filter { it !in setOf("id", "name", "title", "item", "meal") }
            .mapNotNull { key2 ->
                item.optString(key2).takeIf { it.isNotBlank() && it != "null" }?.let { "$key2: $it" }
            }
            .take(3)
            .joinToString(" • ")
        CardBox(text, details)
    }
}

@Composable
fun FamilyPage(db: String) {
    val root = JSONObject(db)
    val app = root.optJSONObject("app") ?: JSONObject()
    val current = profile(db)

    CardBox("Családi kód", app.optString("family_code"))
    CardBox(
        "Te",
        "${current?.optString("avatar").orEmpty()} ${current?.optString("name").orEmpty()} • " +
            "${current?.optString("role").orEmpty()}\n" +
            "Születési dátum: ${current?.optString("birth").orEmpty().ifBlank { "nincs megadva" }}"
    )

    val profiles = arr(db, "profiles")
    for (i in 0 until profiles.length()) {
        val item = profiles.optJSONObject(i) ?: continue
        CardBox(
            "${item.optString("avatar")} ${item.optString("name")}",
            "${item.optString("role")} • ${item.optString("birth").ifBlank { "nincs születési dátum" }}"
        )
    }
}

@Composable
fun AIView(db: String, onDb: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var answer by remember {
        mutableStateOf(
            "Szia ${profile(db)?.optString("name").orEmpty()}! A helyi HomeHub adataiból dolgozom. " +
                "Kérdezhetsz vagy adhatsz egyszerű parancsot."
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardBox("✦ HomeHub AI", answer)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Írj a HomeHub AI-nak…") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = query.isNotBlank(),
            onClick = {
                val result = aiProcess(query, db)
                answer = result.first
                onDb(result.second)
                query = ""
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text("KÜLDÉS")
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "Mi újság?",
                "Mi van a bevásárláson?",
                "Milyen feladatok vannak?",
                "Adj hozzá tejet a bevásárlólistához"
            ).forEach { suggestion ->
                AssistChip(
                    onClick = { query = suggestion },
                    label = { Text(suggestion) }
                )
            }
        }
    }
}

fun aiProcess(query: String, db: String): Pair<String, String> {
    val lower = query.lowercase(Locale.getDefault()).trim()
    val root = JSONObject(db)

    if (lower.isBlank()) {
        return "Írj valamit, a gondolatolvasás még nincs bekötve." to db
    }

    if (lower.contains("bevás") &&
        !lower.contains("adj hozzá") &&
        !lower.contains("add hozzá") &&
        !lower.startsWith("add ")
    ) {
        val names = mutableListOf<String>()
        val lists = arr(db, "shopping")
        for (i in 0 until lists.length()) {
            val items = lists.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                if (!item.optBoolean("bought", false) && item.optString("name").isNotBlank()) {
                    names.add(item.optString("name"))
                }
            }
        }
        return "A nyitott bevásárlás: ${names.joinToString(", ").ifBlank { "jelenleg üres." }}" to db
    }

    if (lower.contains("feladat") || lower.contains("teendő")) {
        return "Nyitott feladatok: ${listNames(db, "tasks").joinToString(", ").ifBlank { "nincs." }}" to db
    }

    if (lower.contains("család") &&
        (lower.contains("hány") || lower.contains("kik") || lower.contains("tag"))
    ) {
        return "Jelenleg ${count(db, "profiles")} családtag van a helyi adatokban." to db
    }

    if (lower.contains("csomag")) {
        return "Jelenleg ${count(db, "packages")} csomag van a rendszerben." to db
    }

    if (lower.contains("kisáll") || lower.contains("állat")) {
        return "Kisállatok: ${listNames(db, "pets").joinToString(", ").ifBlank { "még nincs felvéve." }}" to db
    }

    if (lower.contains("mi újság") || lower.contains("összeg")) {
        return "HomeHub összkép: ${count(db, "profiles")} családtag, " +
            "${shoppingItemCount(db)} bevásárlási tétel, ${count(db, "tasks")} feladat, " +
            "${count(db, "events")} esemény, ${count(db, "pets")} kisállat, " +
            "${count(db, "packages")} csomag." to db
    }

    if (lower.startsWith("adj hozzá") || lower.startsWith("add hozzá") || lower.startsWith("add ")) {
        val regex = Regex(
            "(?:adj hozzá|add hozzá|add)\\s+(.+?)(?:\\s+a bevásárlólistához|\\s+bevásárlólistához)?$",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(query)
        if (match != null) {
            val itemName = match.groupValues[1].trim()
            val currentProfile = profile(db)
            val lists = root.optJSONArray("shopping") ?: JSONArray().also { root.put("shopping", it) }
            if (lists.length() == 0) {
                lists.put(JSONObject().apply {
                    put("id", id())
                    put("name", "Bevásárlólista")
                    put("created_by", currentProfile?.optString("name").orEmpty())
                    put("created", "")
                    put("items", JSONArray())
                })
            }
            lists.optJSONObject(0)?.optJSONArray("items")?.put(JSONObject().apply {
                put("id", id())
                put("name", itemName)
                put("qty", "1")
                put("bought", false)
                put("added_by", currentProfile?.optString("name").orEmpty())
            })
            return "Hozzáadtam a(z) ${lists.optJSONObject(0)?.optString("name", "Bevásárlólista")} listához: $itemName." to root.toString()
        }
    }

    if (lower.contains("mit tudsz") || lower.contains("segíts")) {
        return "Tudok helyi HomeHub-adatokból összefoglalni, listákat lekérdezni és egyszerű műveleteket végrehajtani. " +
            "Például: „Adj hozzá tejet a bevásárlólistához”." to db
    }

    return "Ezt még nem tudom biztosan értelmezni. Próbáld: „Mi újság?”, „Mi van a bevásárláson?” vagy „Adj hozzá kenyeret a bevásárlólistához”." to db
}

@Composable
fun SettingsPage(db: String, onDb: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cloud by remember { mutableStateOf(cloudUrl(context)) }
    var key by remember { mutableStateOf(cloudKey(context)) }
    var status by remember { mutableStateOf("") }
    CardBox("Online / offline", "A HomeHub Cloud tartja a család közös adatait. Internet nélkül a telefon a helyi másolatot használja, majd visszatéréskor automatikusan szinkronizál.")
    Card(colors = CardDefaults.cardColors(Panel), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("HomeHub Cloud", color = TextMain, fontWeight = FontWeight.Bold)
            Text("Egyszer kell beállítani. A PC ezután nem kell 0–24-ben.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(value = cloud, onValueChange = { cloud = it }, label = { Text("Supabase Project URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Supabase anon public key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { saveCloudConfig(context, cloud, key); status = "Cloud beállítás mentve." }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("MENTÉS") }
            Button(onClick = { saveCloudConfig(context, cloud, key); status = "Szinkronizálás…"; scope.launch { cloudSync(context, db).onSuccess { remote -> onDb(remote); status = "Szinkron kész." }.onFailure { status = "Nem sikerült: ${it.message}" } } }, enabled = cloud.isNotBlank() && key.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))) { Text("SZINKRONIZÁLÁS MOST") }
            Text(status, color = Muted, fontSize = 12.sp)
        }
    }
    CardBox("Családi kód", JSONObject(db).optJSONObject("app")?.optString("family_code").orEmpty())
}
