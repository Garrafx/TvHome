package com.raffaele.tvhome

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val SETTINGS_PKG = "__settings"

data class AppEntry(
    val pkg: String,
    val label: String,
    val image: ImageBitmap?,
    val isBanner: Boolean,
)

class MainActivity : ComponentActivity() {

    private val reloadTick = mutableIntStateOf(0)
    private val pinned = mutableStateListOf<String>()
    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadTick.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinned.addAll(prefs.getString("pinned", "").orEmpty().split(",").filter { it.isNotBlank() })
        setContent { Root() }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        enableHomeKeyServiceIfPossible()
    }

    // ---------- logica ----------

    private fun serviceId(short: Boolean): String {
        val cn = ComponentName(this, HomeKeyService::class.java)
        return if (short) cn.flattenToShortString() else cn.flattenToString()
    }

    private fun isHomeKeyServiceOn(): Boolean {
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val list = cur.split(':')
        return list.contains(serviceId(false)) || list.contains(serviceId(true))
    }

    /** Funziona solo dopo: adb shell pm grant com.raffaele.tvhome android.permission.WRITE_SECURE_SETTINGS */
    private fun enableHomeKeyServiceIfPossible() {
        if (isHomeKeyServiceOn()) return
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val id = serviceId(false)
            val new = if (cur.isBlank()) id else "$cur:$id"
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, new)
            Settings.Secure.putString(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        } catch (_: Exception) {}
    }

    private fun launch(app: AppEntry) {
        val i = if (app.pkg == SETTINGS_PKG) Intent(Settings.ACTION_SETTINGS)
        else packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: packageManager.getLaunchIntentForPackage(app.pkg)
        if (i != null) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun togglePin(pkg: String) {
        if (!pinned.remove(pkg)) pinned.add(pkg)
        prefs.edit().putString("pinned", pinned.joinToString(",")).apply()
    }

    private fun showInfo(pkg: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    private fun uninstall(pkg: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    // ---------- UI ----------

    @Composable
    private fun Root() {
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var menuFor by remember { mutableStateOf<AppEntry?>(null) }
        val tick = reloadTick.intValue

        LaunchedEffect(tick) { apps = loadApps(packageManager, packageName) }

        // Il tasto Indietro non deve far uscire dal launcher.
        BackHandler(enabled = menuFor == null) { }

        HomeScreen(
            apps = apps,
            pinned = pinned.toList(),
            homeKeyOn = isHomeKeyServiceOn(),
            onLaunch = { launch(it) },
            onMenu = { if (it.pkg != SETTINGS_PKG) menuFor = it },
        )

        menuFor?.let { app ->
            AppMenu(
                app = app,
                isPinned = app.pkg in pinned,
                onDismiss = { menuFor = null },
                onOpen = { menuFor = null; launch(app) },
                onPin = { togglePin(app.pkg); menuFor = null },
                onInfo = { menuFor = null; showInfo(app.pkg) },
                onUninstall = { menuFor = null; uninstall(app.pkg) },
            )
        }
    }
}

private suspend fun loadApps(pm: PackageManager, self: String): List<AppEntry> =
    withContext(Dispatchers.Default) {
        val found = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val p = ri.activityInfo.packageName
                if (p != self && p !in found) found[p] = ri
            }
        }
        val list = found.values.map { ri ->
            val ai = ri.activityInfo
            val banner = try { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) } catch (_: Exception) { null }
            val bmp = try {
                if (banner != null) banner.toBitmap(320, 180)
                else ai.loadIcon(pm).toBitmap(192, 192)
            } catch (_: Exception) { null }
            AppEntry(ai.packageName, ri.loadLabel(pm).toString(), bmp?.asImageBitmap(), banner != null)
        }.sortedBy { it.label.lowercase() }
        list + AppEntry(SETTINGS_PKG, "Impostazioni", null, false)
    }

@Composable
private fun HomeScreen(
    apps: List<AppEntry>,
    pinned: List<String>,
    homeKeyOn: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    var focused by remember { mutableStateOf<AppEntry?>(null) }
    val accent by animateColorAsState(
        targetValue = focused?.takeIf { it.pkg != SETTINGS_PKG }
            ?.let { Color.hsv((abs(it.pkg.hashCode()) % 360).toFloat(), 0.55f, 0.32f) }
            ?: Color(0xFF1A2540),
        animationSpec = tween(700),
        label = "accent",
    )
    val firstFocus = remember { FocusRequester() }
    val ready = apps.size > 1
    LaunchedEffect(ready) {
        if (ready) {
            delay(150)
            try { firstFocus.requestFocus() } catch (_: Exception) {}
        }
    }
    val pinnedApps = pinned.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent, Color(0xFF05070D))))
    ) {
        val cardW = (maxWidth - 128.dp) / 5.4f
        val cardH = cardW * 9f / 16f

        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!homeKeyOn) {
                    Text("Tasto Home non attivo: vedi il README", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Clock()
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 20.dp).height(96.dp)) {
                Text(
                    focused?.label ?: "Home",
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (focused != null && focused?.pkg != SETTINGS_PKG) {
                    Text(
                        "OK per aprire. Tieni premuto per le opzioni.",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            if (pinnedApps.isNotEmpty()) {
                SectionTitle("Preferiti")
                AppRow(pinnedApps, cardW, cardH, firstFocus, { focused = it }, onLaunch, onMenu)
                Spacer(Modifier.height(10.dp))
            }
            SectionTitle("Tutte le app")
            AppRow(apps, cardW, cardH, if (pinnedApps.isEmpty()) firstFocus else null, { focused = it }, onLaunch, onMenu)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp),
    )
}

@Composable
private fun AppRow(
    items: List<AppEntry>,
    w: Dp,
    h: Dp,
    firstRequester: FocusRequester?,
    onFocus: (AppEntry) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        itemsIndexed(items, key = { _, a -> a.pkg }) { idx, app ->
            AppCard(
                app = app, w = w, h = h,
                requester = if (idx == 0) firstRequester else null,
                onFocus = { onFocus(app) },
                onClick = { onLaunch(app) },
                onLongClick = { onMenu(app) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    app: AppEntry,
    w: Dp,
    h: Dp,
    requester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.14f else 1f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "scale",
    )
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "glow")
    val cardShape = RoundedCornerShape(14.dp)

    Column(Modifier.width(w).zIndex(if (focused) 1f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(w, h)
                .let { if (requester != null) it.focusRequester(requester) else it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = 48f * glow
                    shape = cardShape
                    clip = true
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            if (app.pkg == SETTINGS_PKG) {
                Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A3F55), Color(0xFF1B1E2C)))),
                    contentAlignment = Alignment.Center,
                ) { Text("⚙", fontSize = 34.sp, color = Color.White) }
            } else if (app.isBanner && app.image != null) {
                Image(bitmap = app.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val hue = (abs(app.pkg.hashCode()) % 360).toFloat()
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color.hsv(hue, 0.5f, 0.55f), Color.hsv((hue + 40f) % 360f, 0.6f, 0.25f)))
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    app.image?.let {
                        Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxHeight(0.58f).aspectRatio(1f))
                    }
                }
            }
            // riflesso di luce al focus
            Box(
                Modifier.fillMaxSize().alpha(glow * 0.22f)
                    .background(Brush.linearGradient(listOf(Color.White, Color.Transparent)))
            )
            Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.85f * glow), cardShape))
        }
        Text(
            app.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp).alpha(glow),
        )
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now) {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).replaceFirstChar { it.uppercase() }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(time, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
        Text(date, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun AppMenu(
    app: AppEntry,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        try { first.requestFocus() } catch (_: Exception) {}
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(320.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF01C1F2B)).padding(20.dp)
        ) {
            Text(app.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            MenuItem("Apri", Modifier.focusRequester(first), onOpen)
            MenuItem(if (isPinned) "Rimuovi dai preferiti" else "Aggiungi ai preferiti", Modifier, onPin)
            MenuItem("Informazioni app", Modifier, onInfo)
            MenuItem("Disinstalla", Modifier, onUninstall)
        }
    }
}

@Composable
private fun MenuItem(text: String, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (f) Color.White else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text, color = if (f) Color.Black else Color.White, fontSize = 16.sp)
    }
}
