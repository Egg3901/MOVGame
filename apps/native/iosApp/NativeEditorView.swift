import SwiftUI
import UniformTypeIdentifiers
import shared

private struct ScenarioFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, data.count <= 2_000_000 else { throw CocoaError(.fileReadCorruptFile) }
        text = String(decoding: data, as: UTF8.self)
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

struct NativeEditorView: View {
    @ObservedObject var session: GameSession
    @Environment(\.dismiss) private var dismiss
    @State private var library = NativeCustomLibrary.companion.empty()
    @State private var entries: [NativeCustomEntry] = []
    @State private var draft: String?
    @State private var country = "US"
    @State private var message: String?
    @State private var dirty = false
    @State private var leaving = false
    @State private var deleting: String?
    @State private var importing = false
    @State private var exporting = false
    @State private var exportFile = ScenarioFile(text: "")
    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private func persist() {
        UserDefaults.standard.set(library.json(), forKey: "custom_scenarios_v1")
        entries = library.entries()
    }
    @discardableResult private func save() -> String? {
        guard let draft else { return nil }
        let result = library.save(json: draft, now: now)
        message = result.json == nil ? result.errors.joined(separator: "\n") : "Scenario saved on this device."
        if let json = result.json { self.draft = json; dirty = false; persist() }
        return result.json
    }
    private func play(_ json: String) {
        guard let snapshot = NativeCustomScenario.companion.start(json: json), session.importCampaign(json: snapshot) else {
            message = "Could not start this custom scenario."; return
        }
        draft = nil; dirty = false; dismiss()
    }
    private func binding(_ field: NativeEditorField) -> Binding<String> {
        Binding(get: {
            NativeCustomScenario.companion.fields(json: draft ?? "").first { $0.path == field.path }?.value ?? ""
        }, set: { value in
            guard let draft else { return }
            self.draft = NativeCustomScenario.companion.edit(json: draft, path: field.path, value: value); dirty = true
        })
    }
    var body: some View {
        Form {
            Section {
                Text("Create a casual campaign. Custom scenarios stay off daily and ranked leaderboards. Export JSON to share with the web editor.")
                Text("Your current draft is kept on this device while you edit.").font(.caption)
                if let message { Text(message).font(.caption) }
            }
            if let json = draft {
                draftForm(json)
            } else {
                libraryForm
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            if let saved = UserDefaults.standard.string(forKey: "custom_scenarios_v1"), let restored = NativeCustomLibrary.companion.restore(json: saved) { library = restored }
            entries = library.entries()
            if let savedDraft = UserDefaults.standard.string(forKey: "custom_scenario_draft") { draft = savedDraft; dirty = true }
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--mov-capture-editor") && draft == nil {
                draft = NativeCustomScenario.companion.create(country: "US", id: "custom-capture", election: nil, now: now)
            }
            #endif
        }
        .onChange(of: draft) { value in UserDefaults.standard.set(value, forKey: "custom_scenario_draft") }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 2_000_000 else { message = "Scenario files must be smaller than 2 MB."; return }
                let data = try Data(contentsOf: url)
                guard data.count <= 2_000_000 else { message = "Scenario files must be smaller than 2 MB."; return }
                let validation = NativeCustomScenario.companion.validate(json: String(decoding: data, as: UTF8.self), now: now)
                if let json = validation.json { draft = json; dirty = true; message = "Imported draft. Save to add it to your scenarios." }
                else { message = validation.errors.joined(separator: "\n") }
            } catch { message = error.localizedDescription }
        }
        .fileExporter(isPresented: $exporting, document: exportFile, contentType: .json, defaultFilename: "mov-scenario") { result in
            if case .failure(let error) = result { message = error.localizedDescription }
        }
        .confirmationDialog("Unsaved scenario", isPresented: $leaving, titleVisibility: .visible) {
            Button("Save and return") { if save() != nil { draft = nil } }
            Button("Discard changes", role: .destructive) { draft = nil; dirty = false }
            Button("Keep editing", role: .cancel) { }
        }
        .alert("Delete scenario?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Delete", role: .destructive) { if let id = deleting { library.remove(id: id); persist() }; deleting = nil }
            Button("Cancel", role: .cancel) { deleting = nil }
        } message: { Text("Campaign saves already started from this scenario keep their own rules.") }
    }
    private var libraryForm: some View {
        Group {
            Section("Create a scenario") {
                Picker("Country", selection: $country) {
                    ForEach(MobileCampaign.companion.countries(), id: \.id) { Text("\($0.flag) \($0.name)").tag($0.id) }
                }
                Button("Create scenario") {
                    draft = NativeCustomScenario.companion.create(country: country, id: "custom-\(UUID().uuidString)", election: nil, now: now)
                    dirty = true; message = nil
                }
                Button("Import scenario JSON") { importing = true }
            }
            Section("My scenarios") {
                if entries.isEmpty { Text("Create your first custom election.") }
                ForEach(entries, id: \.id) { entry in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(entry.label).font(.headline).foregroundStyle(CampaignStyle.gold)
                        Text("\(entry.country) · Casual").font(.caption)
                        ViewThatFits {
                            HStack { entryButtons(entry) }
                            VStack(alignment: .leading) { entryButtons(entry) }
                        }
                    }.padding(.vertical, 6)
                }
            }
        }
    }
    @ViewBuilder private func entryButtons(_ entry: NativeCustomEntry) -> some View {
        Button("Edit") { draft = entry.json; dirty = false; message = nil }
        Button("Duplicate") { draft = NativeCustomScenario.companion.duplicate(json: entry.json, id: "custom-\(UUID().uuidString)", now: now); dirty = true }
        Button("Play") { play(entry.json) }
        Button("Delete", role: .destructive) { deleting = entry.id }
    }
    private func draftForm(_ json: String) -> some View {
        let fields = NativeCustomScenario.companion.fields(json: json)
        let sections = fields.reduce(into: [String]()) { if !$0.contains($1.section) { $0.append($1.section) } }
        return Group {
            Section("Custom campaign") {
                Button("Save scenario") { save() }
                Button("Export JSON") { if let saved = save() { exportFile = ScenarioFile(text: saved); exporting = true } }
                Button("Play casual campaign") { if let saved = save() { play(saved) } }
                Button("My scenarios") { if dirty { leaving = true } else { draft = nil } }
                if let entry = NativeCustomScenario.companion.entry(json: json), entry.country != "US" {
                    Menu("Change base election") {
                        ForEach(MobileCampaign.companion.elections(countryId: entry.country), id: \.nativeId) { election in
                            Button(election.label) { draft = NativeCustomScenario.companion.changeElection(json: json, election: election.nativeId); dirty = true }
                        }
                    }
                    Text("Changing the base election resets parties and leader traits to that election.").font(.caption)
                }
            }
            ForEach(sections, id: \.self) { section in
                Section {
                    DisclosureGroup(section) {
                        ForEach(fields.filter { $0.section == section }, id: \.path) { field in
                            fieldView(field)
                        }
                    }.foregroundStyle(CampaignStyle.gold)
                }
            }
        }
    }
    @ViewBuilder private func fieldView(_ field: NativeEditorField) -> some View {
        if field.choices.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(field.label.capitalized).font(.caption).foregroundStyle(.secondary)
                TextField(field.label, text: binding(field)).keyboardType(field.numeric ? .numbersAndPunctuation : .default)
                    .textInputAutocapitalization(.sentences).foregroundStyle(.primary).accessibilityLabel(field.label)
            }
        } else {
            Picker(field.label, selection: binding(field)) { ForEach(field.choices, id: \.self) { Text($0.capitalized).tag($0) } }
        }
    }
}
