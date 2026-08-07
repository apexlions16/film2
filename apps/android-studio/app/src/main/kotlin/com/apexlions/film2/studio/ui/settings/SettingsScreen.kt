package com.apexlions.film2.studio.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.settings.HfAccountToken

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val application = context.applicationContext as Film2StudioApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(application.settingsRepository, application.shardRegistryManager),
    )
    val tokens by viewModel.tokens.collectAsState()
    val hfAccounts by viewModel.hfAccounts.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val addingAccount by viewModel.addingAccount.collectAsState()
    val accountError by viewModel.accountError.collectAsState()
    val backupBusy by viewModel.backupBusy.collectAsState()
    val backupMessage by viewModel.backupMessage.collectAsState()

    var tmdbKey by remember { mutableStateOf("") }
    var githubPat by remember { mutableStateOf("") }
    var newHfToken by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }
    var pendingBackupContent by remember { mutableStateOf<String?>(null) }
    var localBackupError by remember { mutableStateOf<String?>(null) }

    var showTmdb by remember { mutableStateOf(false) }
    var showGithub by remember { mutableStateOf(false) }
    var showNewHfToken by remember { mutableStateOf(false) }

    LaunchedEffect(tokens) {
        if (!loadedOnce) {
            tmdbKey = tokens.tmdbApiKey.orEmpty()
            githubPat = tokens.githubPat.orEmpty()
            loadedOnce = true
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = pendingBackupContent
        pendingBackupContent = null
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(content)
                } ?: error("Yedek dosyasi acilamadi")
            }.onFailure { localBackupError = "Yedek dosyasi yazilamadi: ${it.message}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Yedek dosyasi acilamadi")
            }.onSuccess { raw ->
                viewModel.importBackup(raw) { restored ->
                    tmdbKey = restored.tmdbApiKey.orEmpty()
                    githubPat = restored.githubPat.orEmpty()
                }
            }.onFailure { localBackupError = "Yedek dosyasi okunamadi: ${it.message}" }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(saved) {
        if (saved) {
            snackbarHostState.showSnackbar("Ayarlar kaydedildi")
            viewModel.clearSavedFlag()
        }
    }
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBackupMessage()
        }
    }
    LaunchedEffect(localBackupError) {
        localBackupError?.let {
            snackbarHostState.showSnackbar(it)
            localBackupError = null
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

            HfAccountsSection(
                accounts = hfAccounts,
                newToken = newHfToken,
                onNewTokenChange = { newHfToken = it },
                showNewToken = showNewHfToken,
                onToggleShowNewToken = { showNewHfToken = !showNewHfToken },
                adding = addingAccount,
                error = accountError,
                onAdd = { viewModel.addHfAccount(newHfToken) { newHfToken = "" } },
                onRemove = { namespace -> viewModel.removeHfAccount(namespace) },
            )

            Divider()

            TokenField(
                label = "TMDB API Key",
                value = tmdbKey,
                onValueChange = { tmdbKey = it },
                visible = showTmdb,
                onToggleVisible = { showTmdb = !showTmdb },
            )
            TokenField(
                label = "GitHub PAT (repo write)",
                value = githubPat,
                onValueChange = { githubPat = it },
                visible = showGithub,
                onToggleVisible = { showGithub = !showGithub },
            )

            Button(
                onClick = { viewModel.save(tmdbKey, githubPat) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Kaydet")
            }

            Divider()

            Text("Token yedegi", style = MaterialTheme.typography.titleSmall)
            Text(
                "Yeni APK eski uygulamanin ustune kurulamiyorsa once bu uygulamadan yedek alin. " +
                    "Yedek; TMDB anahtarini, GitHub PAT'i ve tum Hugging Face tokenlarini icerir. " +
                    "Dosyayi kimseyle paylasmayin ve geri yukledikten sonra guvenli bir yerde saklayin veya silin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.exportBackup(tmdbKey, githubPat) { content ->
                            pendingBackupContent = content
                            exportLauncher.launch("film2-studio-token-yedegi.json")
                        }
                    },
                    enabled = !backupBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Yedekle")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !backupBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Geri Yukle")
                }
            }
            if (backupBusy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("Token yedegi isleniyor", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HfAccountsSection(
    accounts: List<HfAccountToken>,
    newToken: String,
    onNewTokenChange: (String) -> Unit,
    showNewToken: Boolean,
    onToggleShowNewToken: () -> Unit,
    adding: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Hugging Face hesaplari",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Birden fazla hesap ekleyebilirsiniz. Aktif hesabin depolama kotasi dolarsa " +
                "yukleme otomatik olarak listedeki bir sonraki hesaba gecer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (accounts.isEmpty()) {
            Text(
                "Henuz hesap eklenmedi. Asagidan bir Hugging Face write token'i ekleyin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { account ->
                    HfAccountRow(account = account, onRemove = { onRemove(account.namespace) })
                }
            }
        }

        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newToken,
                onValueChange = onNewTokenChange,
                label = { Text("Yeni Hugging Face write token'i") },
                singleLine = true,
                visualTransformation = if (showNewToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onToggleShowNewToken) {
                        Icon(
                            imageVector = if (showNewToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showNewToken) "Gizle" else "Goster",
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onAdd,
                enabled = newToken.isNotBlank() && !adding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (adding) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                }
                Text("Hesap ekle")
            }
        }
        Text(
            "Token yapistirilinca hangi hesaba ait oldugu otomatik tespit edilir. Write yetkili token gerekir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HfAccountRow(account: HfAccountToken, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(account.namespace, style = MaterialTheme.typography.bodyMedium)
                if (!account.fullname.isNullOrBlank()) {
                    Text(
                        account.fullname,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onRemove) { Text("Kaldir") }
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
