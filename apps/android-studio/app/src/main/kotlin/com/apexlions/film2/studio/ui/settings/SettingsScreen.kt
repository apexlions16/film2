package com.apexlions.film2.studio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexlions.film2.studio.Film2StudioApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val application = LocalContext.current.applicationContext as Film2StudioApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(application.settingsRepository),
    )
    val tokens by viewModel.tokens.collectAsState()
    val saved by viewModel.saved.collectAsState()

    var tmdbKey by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf("") }
    var githubPat by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }

    var showTmdb by remember { mutableStateOf(false) }
    var showHf by remember { mutableStateOf(false) }
    var showGithub by remember { mutableStateOf(false) }

    LaunchedEffect(tokens) {
        if (!loadedOnce) {
            tmdbKey = tokens.tmdbApiKey.orEmpty()
            hfToken = tokens.huggingFaceToken.orEmpty()
            githubPat = tokens.githubPat.orEmpty()
            loadedOnce = true
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(saved) {
        if (saved) {
            snackbarHostState.showSnackbar("Ayarlar kaydedildi")
            viewModel.clearSavedFlag()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Ayarlar") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Bu tokenlar sadece resmi TMDB / huggingface.co / api.github.com adreslerine " +
                    "Authorization header'i olarak gonderilir ve hicbir yerde loglanmaz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TokenField(
                label = "TMDB API Key",
                value = tmdbKey,
                onValueChange = { tmdbKey = it },
                visible = showTmdb,
                onToggleVisible = { showTmdb = !showTmdb },
            )
            TokenField(
                label = "Hugging Face Yazma Tokeni",
                value = hfToken,
                onValueChange = { hfToken = it },
                visible = showHf,
                onToggleVisible = { showHf = !showHf },
            )
            TokenField(
                label = "GitHub PAT (repo write)",
                value = githubPat,
                onValueChange = { githubPat = it },
                visible = showGithub,
                onToggleVisible = { showGithub = !showGithub },
            )

            Button(
                onClick = { viewModel.save(tmdbKey, hfToken, githubPat) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Kaydet")
            }
        }
    }
}

@Composable
private fun TokenField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Gizle" else "Goster",
                )
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
