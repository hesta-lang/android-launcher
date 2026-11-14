package com.geode.launcher.preferences

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geode.launcher.LauncherDiagnostics
import com.geode.launcher.R
import com.geode.launcher.ui.theme.GeodeLauncherTheme
import com.geode.launcher.ui.theme.LocalTheme
import com.geode.launcher.ui.theme.Theme
import java.io.File
import java.text.DateFormat
import java.util.Date

class LauncherDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeOption by com.geode.launcher.utils.PreferenceUtils.useIntPreference(
                com.geode.launcher.utils.PreferenceUtils.Key.THEME
            )
            val theme = Theme.fromInt(themeOption)
            val backgroundOption by com.geode.launcher.utils.PreferenceUtils.useBooleanPreference(
                com.geode.launcher.utils.PreferenceUtils.Key.BLACK_BACKGROUND
            )
            val dynamicColorOption by com.geode.launcher.utils.PreferenceUtils.useBooleanPreference(
                com.geode.launcher.utils.PreferenceUtils.Key.DISABLE_USER_THEME
            )

            CompositionLocalProvider(LocalTheme provides theme) {
                GeodeLauncherTheme(
                    theme = theme,
                    blackBackground = backgroundOption,
                    dynamicColor = !dynamicColorOption
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LauncherDiagnosticsScreen(onBackPressedDispatcher)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherDiagnosticsScreen(onBackPressedDispatcher: OnBackPressedDispatcher?) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LauncherDiagnostics.isEnabled(context)) }
    val sessions = remember { LauncherDiagnostics.listSessions(context) }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        val source = pendingExport
        if (uri != null && source != null && source.isFile) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                Toast.makeText(
                    context,
                    R.string.launcher_diagnostics_exported,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    error.message ?: "Export failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        pendingExport = null
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onBackPressedDispatcher?.onBackPressed() }) {
                        Icon(
                            painterResource(R.drawable.icon_arrow_back),
                            contentDescription = stringResource(R.string.back_icon_alt)
                        )
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.title_activity_launcher_diagnostics),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.launcher_diagnostics_auto_record),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.launcher_diagnostics_auto_record_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                LauncherDiagnostics.setEnabled(context, it)
                            }
                        )
                    }
                    Text(
                        stringResource(R.string.launcher_diagnostics_scope_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                stringResource(R.string.launcher_diagnostics_sessions),
                style = MaterialTheme.typography.titleMedium
            )

            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.launcher_diagnostics_no_sessions),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                sessions.forEach { file ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    DateFormat.getDateTimeInstance().format(Date(file.lastModified())) +
                                        " • " + (file.length() / 1024L) + " KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                pendingExport = file
                                exportLauncher.launch(file.name)
                            }) {
                                Text(stringResource(R.string.launcher_diagnostics_export))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
