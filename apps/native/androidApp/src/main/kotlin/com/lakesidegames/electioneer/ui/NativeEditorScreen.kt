package com.lakesidegames.electioneer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lakesidegames.electioneer.engine.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeEditorScreen(session: GameSession) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mov_native", 0) }
    val library = remember { prefs.getString("custom_scenarios_v1", null)?.let(NativeCustomLibrary::restore) ?: NativeCustomLibrary.empty() }
    var entries by remember { mutableStateOf(library.entries()) }
    var draft by remember { mutableStateOf(prefs.getString("custom_scenario_draft", null)) }
    var dirty by remember { mutableStateOf(draft != null) }
    var leave by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf("US") }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun persist() { prefs.edit().putString("custom_scenarios_v1", library.json()).apply(); entries = library.entries() }
    fun save(): String? {
        val result = draft?.let { library.save(it, System.currentTimeMillis()) } ?: return null
        message = if (result.json == null) result.errors.joinToString("\n") else "Scenario saved on this device."
        if (result.json != null) { draft = result.json; dirty = false; persist() }
        return result.json
    }
    LaunchedEffect(draft) { prefs.edit().putString("custom_scenario_draft", draft).apply() }
    BackHandler(enabled = draft != null) { if (dirty) leave = true else draft = null }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var count = stream.read(buffer)
                        while (count >= 0) {
                            check(out.size() + count <= 2_000_000) { "Scenario files must be smaller than 2 MB." }
                            out.write(buffer, 0, count); count = stream.read(buffer)
                        }
                        val bytes = out.toByteArray()
                        check(bytes.size <= 2_000_000) { "Scenario files must be smaller than 2 MB." }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not open scenario.")
                }
                val result = NativeCustomScenario.validate(json, System.currentTimeMillis())
                if (result.json != null) { draft = result.json; dirty = true; message = "Imported draft. Save to add it to your scenarios." }
                else message = result.errors.joinToString("\n")
            }.onFailure { message = it.message }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exporting; exporting = null
        if (uri != null && json != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Could not export scenario.")
                }
                message = "Scenario exported."
            }.onFailure { message = it.message }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SCENARIO EDITOR", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("Create a casual campaign. Custom scenarios stay off daily and ranked leaderboards. Save and export them to share with the web editor.")
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val json = draft
        if (json == null) {
            Text("Country")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MobileCampaign.countries().forEach { c -> FilterChip(country == c.id, { country = c.id }, label = { Text("${c.flag} ${c.id}") }) }
            }
            Button(onClick = { draft = NativeCustomScenario.create(country, "custom-${UUID.randomUUID()}", null, System.currentTimeMillis()); dirty = true; message = null }) { Text("Create scenario") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import scenario JSON") }
            entries.forEach { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.label, style = MaterialTheme.typography.titleMedium)
                        Text("${entry.country} · Casual")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { draft = entry.json; dirty = false; message = null }) { Text("Edit") }
                            TextButton(onClick = { draft = NativeCustomScenario.duplicate(entry.json, "custom-${UUID.randomUUID()}", System.currentTimeMillis()); dirty = true }) { Text("Duplicate") }
                            TextButton(onClick = { NativeCustomScenario.start(entry.json)?.let { session.importCampaign(it) } }) { Text("Play") }
                            TextButton(onClick = { deleting = entry.id }) { Text("Delete") }
                        }
                    }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save() }) { Text("Save") }
                OutlinedButton(onClick = { save()?.let { exporting = it; exporter.launch("mov-scenario.json") } }) { Text("Export JSON") }
                OutlinedButton(onClick = { save()?.let(NativeCustomScenario::start)?.let { session.importCampaign(it) } }) { Text("Play casual") }
                TextButton(onClick = { if (dirty) leave = true else draft = null }) { Text("My scenarios") }
            }
            val entry = NativeCustomScenario.entry(json)
            if (entry != null && entry.country != "US") {
                var choices by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { choices = true }) { Text("Change base election") }
                DropdownMenu(choices, { choices = false }) {
                    MobileCampaign.elections(entry.country).forEach { election -> DropdownMenuItem(text = { Text(election.label) }, onClick = {
                        draft = NativeCustomScenario.changeElection(json, election.nativeId); dirty = true; choices = false
                    }) }
                }
                Text("Changing the base election resets parties and leader traits to that election.", style = MaterialTheme.typography.bodySmall)
            }
            NativeCustomScenario.fields(json).groupBy { it.section }.forEach { (section, fields) ->
                var expanded by remember(section) { mutableStateOf(section == "Campaign") }
                OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text("${if (expanded) "▾" else "▸"} $section") }
                if (expanded) fields.forEach { field ->
                    if (field.choices.isNotEmpty()) {
                        Text(field.label)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { field.choices.forEach { value -> FilterChip(field.value == value, {
                            draft = NativeCustomScenario.edit(draft ?: json, field.path, value); dirty = true
                        }, label = { Text(value) }) } }
                    } else {
                        OutlinedTextField(field.value, { value -> draft = NativeCustomScenario.edit(draft ?: json, field.path, value); dirty = true },
                            label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Decimal else KeyboardType.Text))
                    }
                }
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("Unsaved scenario") }, text = { Text("Save your changes before leaving?") },
        confirmButton = { TextButton(onClick = { if (save() != null) { draft = null; leave = false } }) { Text("Save") } },
        dismissButton = { Row { TextButton(onClick = { leave = false }) { Text("Keep editing") }; TextButton(onClick = { draft = null; dirty = false; leave = false }) { Text("Discard") } } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete scenario?") }, text = { Text("Campaign saves already started from this scenario keep their own rules.") },
        confirmButton = { TextButton(onClick = { library.remove(id); persist(); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
