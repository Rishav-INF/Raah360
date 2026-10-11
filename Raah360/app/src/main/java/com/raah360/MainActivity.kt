package com.raah360

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.raah360.ui.HomeScreen
import com.raah360.ui.LocalLang
import com.raah360.ui.MapperScreen
import com.raah360.ui.MarkersScreen
import com.raah360.ui.NavigateScreen
import com.raah360.ui.PinDetailScreen
import com.raah360.ui.R360
import com.raah360.ui.Raah360Theme
import com.raah360.ui.SimulatorScreen
import com.raah360.ui.VenuesScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.openDeepLink(intent?.data)
        setContent {
            Raah360Theme {
                Box(Modifier.fillMaxSize().background(R360.Bg)) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.openDeepLink(intent.data)
    }
}

@Composable
fun App(vm: AppViewModel) {
    val screen by vm.screen.collectAsState()
    val msg by vm.message.collectAsState()
    val lang by vm.lang.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(msg) {
        msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast(null) }
    }
    BackHandler(enabled = screen != Screen.Home) { vm.back() }
    CompositionLocalProvider(LocalLang provides lang) { when (val s = screen) {
        Screen.Home -> VenuesScreen(vm)
        Screen.Map -> HomeScreen(vm)
        is Screen.PinDetail -> PinDetailScreen(vm, s.pinId)
        is Screen.Navigate -> NavigateScreen(vm, s.pinId)
        is Screen.Simulator -> SimulatorScreen(vm, s.pinId)
        Screen.Mapper -> MapperScreen(vm)
        Screen.Markers -> MarkersScreen(vm)
    } }
}
