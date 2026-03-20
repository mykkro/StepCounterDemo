package com.example.stepcounterdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stepcounterdemo.ui.theme.StepCounterDemoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StepCounterDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StepCounterScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun StepCounterScreen(
    modifier: Modifier = Modifier,
    viewModel: StepCounterViewModel = viewModel()
) {
    val context = LocalContext.current

    // Track ACTIVITY_RECOGNITION permission (required on API 29+)
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // permission not needed below Android 10
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // Ask for permission once on first composition
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    val isRunning by viewModel.isRunning.collectAsState()
    val stepCount by viewModel.stepCount.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val activeSensorMode by viewModel.activeSensorMode.collectAsState()
    val preferredMode by viewModel.preferredMode.collectAsState()

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Elapsed time
        Text(
            text = timeString,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Step count
        Text(
            text = "$stepCount steps",
            style = MaterialTheme.typography.displayMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ---- Source selector (only shown when not running) ----
        if (!isRunning) {
            Text(
                text = "Step source",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viewModel.stepCounterAvailable) {
                    SourceButton(
                        label = "Step counter",
                        selected = preferredMode == StepCounterViewModel.SensorMode.STEP_COUNTER,
                        enabled = !isRunning,
                        onClick = { viewModel.setPreferredMode(StepCounterViewModel.SensorMode.STEP_COUNTER) }
                    )
                }
                if (viewModel.accelerometerAvailable) {
                    SourceButton(
                        label = "Accelerometer",
                        selected = preferredMode == StepCounterViewModel.SensorMode.ACCELEROMETER,
                        enabled = !isRunning,
                        onClick = { viewModel.setPreferredMode(StepCounterViewModel.SensorMode.ACCELEROMETER) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Start / Stop buttons
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(
                onClick = { viewModel.start(hasPermission) },
                enabled = !isRunning
            ) {
                Text("Start")
            }
            Button(
                onClick = { viewModel.stop() },
                enabled = isRunning
            ) {
                Text("Stop")
            }
        }

        // Show active sensor mode while running or after a session
        if (isRunning || stepCount > 0 || elapsedSeconds > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            val modeLabel = when (activeSensorMode) {
                StepCounterViewModel.SensorMode.STEP_COUNTER -> "Using: hardware step counter"
                StepCounterViewModel.SensorMode.ACCELEROMETER -> "Using: accelerometer"
                StepCounterViewModel.SensorMode.NONE -> "No sensor available"
            }
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors()
        ) {
            Text(label)
        }
    }
}
