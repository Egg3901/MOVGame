package com.lakesidegames.electioneer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CampaignSavesScreen(session: GameSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saves by session.namedSaves.collectAsState()
    val notice by session.saveNotice.collectAsState()
    val account = session.account
    val user by account.user.collectAsState()
    val busy by account.busy.collectAsState()
    val cloud by account.cloudSaves.collectAsState()
    val remoteNotice by account.notice.collectAsState()
    var name by remember { mutableStateOf("") }
    var fileNotice by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var deletingLocal by remember { mutableStateOf<String?>(null) }
    var deletingCloud by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf("") }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val data = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var count = stream.read(buffer)
                        while (count >= 0) {
                            check(data.size() + count <= 2_000_000) { "Save file is too large." }
                            data.write(buffer, 0, count)
                            count = stream.read(buffer)
                        }
                        data.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("Could not open save file.")
                }
                session.importCampaign(json)
            }.onFailure { fileNotice = it.message }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exporting
        exporting = null
        if (uri != null && json != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Could not write save file.")
                }
                fileNotice = "Campaign exported."
            }.onFailure { fileNotice = it.message }
        }
    }
    LaunchedEffect(user?.id) { if (user != null) account.loadCloudSaves() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SAVED CAMPAIGNS", style = MaterialTheme.typography.headlineSmall)
        Text("Local saves work offline. U.S. cloud saves also open in the web game.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it }, label = { Text("Save name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (session.saveNamed(name)) name = "" }, enabled = name.isNotBlank() && session.currentSnapshot() != null) { Text("Save current campaign") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import file") }
            OutlinedButton(onClick = {
                exporting = session.exportCampaign()
                if (exporting != null) exportFile.launch("mov-campaign.json")
            }, enabled = session.currentSnapshot() != null) { Text("Export current") }
        }
        listOfNotNull(fileNotice, notice, remoteNotice).distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        saves.forEach { save ->
            val document = save.document
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(save.name, style = MaterialTheme.typography.titleSmall)
                    Text("${document?.label ?: "Campaign"} · Week ${document?.turn ?: 0}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { session.loadNamed(save.id) }) { Text("Load") }
                        TextButton(onClick = { renaming = save.id; rename = save.name }) { Text("Rename") }
                        TextButton(onClick = { deletingLocal = save.id }) { Text("Delete") }
                    }
                    if (user != null && document?.engine == "us") Row {
                        TextButton(onClick = { session.uploadSave(save.id) }, enabled = !busy) { Text("Upload") }
                        TextButton(onClick = { session.uploadSaveAsNew(save.id) }, enabled = !busy) { Text("Upload as new") }
                    }
                }
            }
        }
        Text("CLOUD SAVES", style = MaterialTheme.typography.titleMedium)
        if (user == null) TextButton(onClick = { session.go(Screen.ACCOUNT) }) { Text("Sign in to sync saves") }
        else {
            TextButton(onClick = account::loadCloudSaves, enabled = !busy) { Text("Refresh cloud saves") }
            cloud.forEach { save ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${save.name} · Week ${save.turn}")
                        Row {
                            TextButton(onClick = { session.downloadSave(save.id) }, enabled = !busy) { Text("Download") }
                            TextButton(onClick = { deletingCloud = save.id }, enabled = !busy) { Text("Delete online") }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { session.go(Screen.ACCOUNT) }) { Text("Back to account") }
    }
    if (renaming != null) AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename campaign") },
        text = { OutlinedTextField(rename, { rename = it }) }, confirmButton = { TextButton(onClick = {
            session.renameSave(renaming!!, rename); renaming = null
        }, enabled = rename.isNotBlank()) { Text("Rename") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    if (deletingLocal != null || deletingCloud != null) AlertDialog(onDismissRequest = { deletingLocal = null; deletingCloud = null },
        title = { Text(if (deletingCloud != null) "Delete online save?" else "Delete local save?") },
        text = { Text("The current campaign will keep running.") }, confirmButton = { TextButton(onClick = {
            deletingLocal?.let(session::deleteLocalSave); deletingCloud?.let(account::deleteCloudSave)
            deletingLocal = null; deletingCloud = null
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletingLocal = null; deletingCloud = null }) { Text("Cancel") } })
}
