package com.fer.a53performance.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fer.a53performance.model.AppUiState
import com.fer.a53performance.ui.components.*
import com.fer.a53performance.ui.theme.A53Muted
import com.fer.a53performance.util.ExternalApps

@Composable
fun ToolsScreen(context: Context, state: AppUiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenHeader("Herramientas", "Control seguro", "Accesos a configuraciones reales de Android y diagnóstico de tu sesión.")
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Diagnóstico", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text("Estado térmico: ${state.stats.thermalLabel}")
            Text("Batería: ${state.stats.batteryPercent}% · %.1f °C · ${state.stats.batteryVoltageMv} mV".format(state.stats.batteryTemperatureC), color = A53Muted)
            Text("RAM: %.1f / %.1f GB".format(state.stats.ramUsedGb, state.stats.ramTotalGb), color = A53Muted)
            Text("Pantalla: %.0f Hz · Red: ${state.stats.networkLabel}".format(state.stats.refreshRateHz), color = A53Muted)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Ajustes del sistema", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            ActionRow(Icons.Rounded.DisplaySettings, "Pantalla", "Brillo, fluidez y ajustes visuales", onClick = { ExternalApps.openDisplaySettings(context) })
            ActionRow(Icons.Rounded.BatterySaver, "Ahorro de energía", "Abrir el panel oficial de batería", onClick = { ExternalApps.openBatterySaverSettings(context) })
            ActionRow(Icons.Rounded.Apps, "Administrar aplicaciones", "Batería, permisos y almacenamiento por app", onClick = { ExternalApps.openAppManagement(context) })
            ActionRow(Icons.Rounded.DoNotDisturbOn, "Acceso a No molestar", "Necesario para Modo Clase y Gaming", onClick = { ExternalApps.openDndAccess(context) })
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Importante", fontWeight = FontWeight.Bold)
            Text("Android no permite a una app normal cambiar libremente frecuencias de CPU/GPU, cerrar cualquier proceso o forzar 120 Hz. Esta versión usa APIs y paneles oficiales; el motor Shizuku/ADB se mantiene separado para no disfrazar acciones falsas como optimizaciones.", color = A53Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(90.dp))
    }
}
