import SwiftUI
import UniformTypeIdentifiers
import shared

struct CloudSaveMeta: Decodable, Identifiable {
    let id: String
    let name: String
    let turn: Int
    let updatedAt: Int64
}
struct DownloadedCampaign {
    let id: String
    let name: String
    let state: String
    let replay: String?
    let updatedAt: Int64
}
private struct CloudSaveList: Decodable { let saves: [CloudSaveMeta]; let versionPreconditions: Bool? }
private struct SaveUploadResponse: Decodable { let updatedAt: Int64 }

extension CampaignAccount {
    func loadCloudSaves() async {
        guard user != nil, !busy else { return }
        busy = true; defer { busy = false }
        do { cloudSaves = try JSONDecoder().decode(CloudSaveList.self, from: await call(path: "/api/saves")).saves }
        catch { message = error.localizedDescription }
    }
    func uploadSave(id: String, payload: String) async -> Int64? {
        guard user != nil, !busy, NativeSaveLibrary.companion.validId(id: id) else { return nil }
        busy = true; message = nil; defer { busy = false }
        do {
            let capabilities = try JSONDecoder().decode(CloudSaveList.self, from: await call(path: "/api/saves"))
            guard capabilities.versionPreconditions == true else {
                message = "Cloud save service needs an update. Your campaign is saved on this device."; return nil
            }
            let data = try await call(path: "/api/saves/\(id)", method: "PUT", body: Data(payload.utf8))
            let response = try JSONDecoder().decode(SaveUploadResponse.self, from: data)
            message = "Campaign saved to the cloud."
            cloudSaves = try JSONDecoder().decode(CloudSaveList.self, from: await call(path: "/api/saves")).saves
            return response.updatedAt
        } catch { message = error.localizedDescription; return nil }
    }
    func downloadSave(id: String) async -> DownloadedCampaign? {
        guard user != nil, !busy, NativeSaveLibrary.companion.validId(id: id) else { return nil }
        busy = true; message = nil; defer { busy = false }
        do {
            let data = try await call(path: "/api/saves/\(id)")
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let state = object["state"] as? [String: Any], let name = object["name"] as? String,
                  let updated = object["updatedAt"] as? NSNumber else { throw CocoaError(.fileReadCorruptFile) }
            let encoded = try JSONSerialization.data(withJSONObject: state)
            let replay = try (object["replay"] as? [String: Any]).map { String(decoding: try JSONSerialization.data(withJSONObject: $0), as: UTF8.self) }
            return DownloadedCampaign(id: id, name: name, state: String(decoding: encoded, as: UTF8.self), replay: replay, updatedAt: updated.int64Value)
        } catch { message = error.localizedDescription; return nil }
    }
    func deleteCloudSave(id: String) async {
        guard user != nil, !busy, NativeSaveLibrary.companion.validId(id: id) else { return }
        busy = true; message = nil; defer { busy = false }
        do {
            _ = try await call(path: "/api/saves/\(id)", method: "DELETE")
            cloudSaves = try JSONDecoder().decode(CloudSaveList.self, from: await call(path: "/api/saves")).saves
        } catch { message = error.localizedDescription }
    }
}

private struct CampaignFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        text = String(decoding: data, as: UTF8.self)
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

struct CampaignSavesView: View {
    @ObservedObject var session: GameSession
    @ObservedObject private var account: CampaignAccount
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var importing = false
    @State private var exporting = false
    @State private var document: CampaignFile?
    @State private var fileNotice: String?
    @State private var deleteLocal: String?
    @State private var deleteCloud: String?
    @State private var renameId: String?
    @State private var renamed = ""
    init(session: GameSession) { self.session = session; self.account = session.account }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("SAVED CAMPAIGNS").font(.title2.bold()).foregroundStyle(CampaignStyle.gold)
                Text("Local saves work offline. When signed in, U.S. autosaves and named saves sync with the web. Conflicting cloud saves need your choice.").font(.caption)
                TextField("Save name", text: $name)
                Button("Save current campaign") { if session.saveNamed(name: name) { name = "" } }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || !session.hasGame)
                HStack {
                    Button("Import file") { importing = true }
                    Button("Export current") {
                        guard let text = session.exportCampaign() else { return }
                        document = CampaignFile(text: text); exporting = true
                    }.disabled(!session.hasGame)
                }
                ForEach(Array(Set([fileNotice, session.saveNotice, account.message].compactMap { $0 })).sorted(), id: \.self) { Text($0).font(.caption) }
                ForEach(session.namedSaves, id: \.id) { save in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(save.name).font(.headline)
                        Text("\(save.document?.label ?? "Campaign") · Week \(save.document?.turn ?? 0)").font(.caption)
                        HStack {
                            Button("Load") { if session.loadNamed(id: save.id) { dismiss() } }
                            Button("Rename") { renameId = save.id; renamed = save.name }
                            Button("Delete") { deleteLocal = save.id }
                        }
                        if account.user != nil, save.document?.engine == "us" {
                            HStack {
                                Button("Upload") { Task { await session.uploadSave(id: save.id) } }
                                Button("Upload as new") { Task { await session.uploadSaveAsNew(id: save.id) } }
                            }.disabled(account.busy)
                        }
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                }
                if account.user != nil {
                    if let notice = session.cloudSyncNotice { Text(notice).font(.caption).foregroundStyle(CampaignStyle.muted) }
                    Button("Retry cloud sync") { session.retryCloudSync() }.disabled(account.busy)
                }
                Text("CLOUD SAVES").font(.headline).foregroundStyle(CampaignStyle.gold)
                if account.user == nil { Text("Sign in from Account to sync saves.").font(.caption) }
                else {
                    Button("Refresh cloud saves") { Task { await account.loadCloudSaves() } }.disabled(account.busy)
                    ForEach(account.cloudSaves) { save in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(save.name) · Week \(save.turn)").font(.headline)
                            HStack {
                                Button("Download") { Task { await session.downloadSave(id: save.id) } }
                                Button("Delete online") { deleteCloud = save.id }
                            }.disabled(account.busy)
                        }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                            .background(CampaignStyle.card, in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }.padding(20)
        }
        .textFieldStyle(.roundedBorder)
        .task(id: account.user?.id) { await account.loadCloudSaves() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 2_000_000 else { throw NSError(domain: "MOVSave", code: 1, userInfo: [NSLocalizedDescriptionKey: "Save file is too large."]) }
                if session.importCampaign(json: try String(contentsOf: url, encoding: .utf8)) { dismiss() }
            } catch { fileNotice = error.localizedDescription }
        }
        .fileExporter(isPresented: $exporting, document: document, contentType: .json, defaultFilename: "mov-campaign") { result in
            switch result { case .success: fileNotice = "Campaign exported."; case .failure(let error): fileNotice = error.localizedDescription }
        }
        .alert("Rename campaign", isPresented: Binding(get: { renameId != nil }, set: { if !$0 { renameId = nil } })) {
            TextField("Name", text: $renamed)
            Button("Rename") { if let id = renameId { session.renameSave(id: id, name: renamed) }; renameId = nil }
            Button("Cancel", role: .cancel) { renameId = nil }
        }
        .alert(deleteCloud == nil ? "Delete local save?" : "Delete online save?", isPresented: Binding(get: { deleteLocal != nil || deleteCloud != nil }, set: { if !$0 { deleteLocal = nil; deleteCloud = nil } })) {
            Button("Delete", role: .destructive) {
                if let id = deleteLocal { session.deleteLocalSave(id: id) }
                if let id = deleteCloud { Task { await account.deleteCloudSave(id: id) } }
                deleteLocal = nil; deleteCloud = nil
            }
            Button("Cancel", role: .cancel) { deleteLocal = nil; deleteCloud = nil }
        } message: { Text("The current campaign will keep running.") }
        .background(CampaignStyle.background).preferredColorScheme(.dark)
    }
}
