import SwiftUI
import PhotosUI
import UIKit

/// Record a shared bill: split equally, or per item after scanning a receipt. The server allocates leftover centavos.
struct ExpenseForm: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var merchant = ""
    @State private var amount = ""
    @State private var date = Date()
    @State private var categoryID = 0
    @State private var payerID = 0
    @State private var sharedBy: Set<Int> = []
    // Filled by "Scan a receipt".
    @State private var lines: [LineState] = []
    @State private var scanID: Int?
    @State private var warnings: [String] = []
    @State private var scanning = false
    @State private var photo: PhotosPickerItem?
    @State private var showCamera = false

    struct LineState: Identifiable { let id = UUID(); let description: String; let amountMinor: Int; var members: Set<Int> }

    private var members: [TripMember] { detail.trip.members ?? [] }
    private var totalMinor: Int? { TravelFormat.minor(amount) }
    private var itemized: Bool { !lines.isEmpty }
    private var lineSum: Int { lines.reduce(0) { $0 + $1.amountMinor } }
    private var valid: Bool {
        guard !title.isEmpty, categoryID != 0, let total = totalMinor else { return false }
        return itemized ? total == lineSum && lines.allSatisfy { !$0.members.isEmpty } : !sharedBy.isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                scanSection
                Section("The bill") {
                    TextField("Description, e.g. Dinner", text: $title)
                    TextField("Merchant", text: $merchant)
                    Picker("Expense type", selection: $categoryID) {
                        ForEach(detail.categories) { category in Text(category.name).tag(category.id) }
                    }
                    TextField("Total (\(detail.trip.currency_code))", text: $amount).keyboardType(.decimalPad)
                    DatePicker("Date", selection: $date, displayedComponents: .date)
                }
                Section("Who covered the full bill?") {
                    Picker("Paid by", selection: $payerID) {
                        Text("Not paid yet").tag(0)
                        ForEach(members) { member in Text(member.user.name).tag(member.id) }
                    }
                }
                if itemized { itemSections } else { equalSection }
            }
            .navigationTitle("New expense").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(!valid || store.busy || scanning)
                }
            }
            .onAppear {
                if categoryID == 0 { categoryID = detail.categories.first?.id ?? 0 }
                if sharedBy.isEmpty { sharedBy = Set(members.map(\.id)) }
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    defer { photo = nil }
                    if let data = try? await item.loadTransferable(type: Data.self) { await scan(data) }
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { data in Task { await scan(data) } }.ignoresSafeArea()
            }
        }
    }

    private var scanSection: some View {
        Section {
            HStack(spacing: 12) {
                if scanning { ProgressView() } else { Image(systemName: "doc.text.viewfinder").font(.title2).foregroundStyle(Theme.forest) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(scanning ? "Reading your receipt…" : scanID != nil ? "Filled in from your receipt" : "Scan a receipt").font(.subheadline.bold())
                    Text(scanning ? "This takes a few seconds." : scanID != nil ? "Check the amounts and who shares each item." : "We’ll fill in the items for you.").font(.caption).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 10) {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button { showCamera = true } label: { Label("Take photo", systemImage: "camera.fill").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).tint(Theme.forest)
                }
                PhotosPicker(selection: $photo, matching: .images) { Label("Choose", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity) }.buttonStyle(.bordered).tint(Theme.forest)
            }
            .disabled(scanning || store.busy)
            ForEach(warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(Color(red: 0.48, green: 0.33, blue: 0.07))
            }
        }
    }

    private var equalSection: some View {
        Section {
            ForEach(members) { member in
                Toggle(member.user.name, isOn: Binding(
                    get: { sharedBy.contains(member.id) },
                    set: { on in if on { sharedBy.insert(member.id) } else { sharedBy.remove(member.id) } }
                ))
            }
        } header: { Text("Split equally between") } footer: {
            if let total = totalMinor, !sharedBy.isEmpty {
                Text("About \(TravelFormat.money(total / sharedBy.count, currency: detail.trip.currency_code)) each.")
            }
        }
    }

    @ViewBuilder private var itemSections: some View {
        Section {
            ForEach($lines) { $line in
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Text(line.description).font(.subheadline.bold()); Spacer(); Text(TravelFormat.money(line.amountMinor, currency: detail.trip.currency_code)).font(.subheadline.bold()) }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(members) { member in
                                let on = line.members.contains(member.id)
                                Button { if on { line.members.remove(member.id) } else { line.members.insert(member.id) } } label: {
                                    Label(member.user.name, systemImage: on ? "checkmark" : "plus").font(.caption.bold())
                                        .padding(.horizontal, 10).padding(.vertical, 6)
                                        .background(on ? Theme.forest : Color.gray.opacity(0.12), in: Capsule())
                                        .foregroundStyle(on ? Color.white : Color.primary)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                    if line.members.isEmpty { Text("Pick at least one person").font(.caption).foregroundStyle(.red) }
                }.padding(.vertical, 4)
            }
        } header: { Text("Who had what?") } footer: {
            if let total = totalMinor, total != lineSum {
                Text("Items add up to \(TravelFormat.money(lineSum, currency: detail.trip.currency_code)). Make the total match, or split equally instead.").foregroundStyle(.red)
            }
        }
        Section { Button("Split equally instead") { lines = [] } }
    }

    private func scan(_ data: Data) async {
        guard let jpeg = CoverPhoto.jpeg(from: data, maxSide: 2000) else { store.error = "That photo couldn’t be opened. Try another one."; return }
        scanning = true
        defer { scanning = false }
        var draft: ReceiptDraft?
        _ = await store.perform { draft = try await store.repository.scanReceipt(tripID: detail.trip.id, jpeg: jpeg) }
        guard let draft else { return }
        scanID = draft.scan_id
        title = draft.title
        if let value = draft.merchant { merchant = value }
        if let value = draft.incurred_at { date = TravelFormat.parse(value) }
        if let id = draft.category_id, detail.categories.contains(where: { $0.id == id }) { categoryID = id }
        if let total = draft.total_minor { amount = String(format: "%.2f", Double(total) / 100) }
        let everyone = Set(members.map(\.id))
        lines = draft.lines.map { LineState(description: $0.description, amountMinor: $0.amount_minor, members: everyone) }
        warnings = draft.warnings
    }

    private func save() async {
        guard let total = totalMinor else { return }
        var input = ExpenseInput(
            title: title, merchant: merchant, category_id: categoryID,
            incurred_at: TravelFormat.date(date, timezone: detail.trip.timezone),
            total_minor: total, split_method: itemized ? "itemized" : "equal",
            payments: payerID == 0 ? [] : [.init(trip_member_id: payerID, amount_minor: total)],
            allocations: itemized ? [] : sharedBy.sorted().map { .init(trip_member_id: $0, weight: 1) }
        )
        input.items = lines.map { .init(description: $0.description, amount_minor: $0.amountMinor, member_ids: $0.members.sorted()) }
        input.receipt_scan_id = scanID
        let saved = await store.mutate { try await store.repository.createExpense(tripID: detail.trip.id, input: input) }
        if saved { dismiss() }
    }
}

/// The system camera, returning the photo as JPEG data.
struct CameraPicker: UIViewControllerRepresentable {
    let onCapture: (Data) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.95) { parent.onCapture(data) }
            parent.dismiss()
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

/// Who owes whom, recording money sent, and confirming money received.
struct BalancesView: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @State private var recording = false

    var body: some View {
        List {
            Section {
                ForEach(detail.balances) { balance in
                    HStack {
                        Text(balance.name)
                        Spacer()
                        Text(label(balance.net_minor))
                            .fontWeight(.semibold)
                            .foregroundStyle(balance.net_minor < 0 ? Color.red : Theme.forest)
                    }
                }
            } header: { Text("Balances") } footer: {
                Text("Completed and closed expenses count here. Reimbursements count once the recipient confirms.")
            }
            if (detail.trip.members ?? []).count > 1 {
                Section { Button("Record money you sent", systemImage: "arrow.up.right.circle") { recording = true } }
            }
            Section("Payment history") {
                if (detail.trip.settlements ?? []).isEmpty { Text("No reimbursements recorded yet.").foregroundStyle(.secondary) }
                ForEach(detail.trip.settlements ?? []) { payment in
                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(detail.name(payment.from_member_id)) \(payment.status == "pending" ? "reported paying" : "paid") \(detail.name(payment.to_member_id))")
                            .font(.subheadline.weight(.semibold))
                        Text("\(TravelFormat.money(payment.amount_minor, currency: detail.trip.currency_code)) · \(payment.payment_method.replacingOccurrences(of: "_", with: " ")) · \(payment.status.capitalized)")
                            .font(.caption).foregroundStyle(.secondary)
                        if payment.status == "pending" && payment.to_member_id == detail.current_member_id {
                            Button("Confirm I received it") {
                                Task { await store.mutate { try await store.repository.confirmSettlement(tripID: detail.trip.id, settlementID: payment.id) } }
                            }
                        }
                    }.padding(.vertical, 4)
                }
            }
        }
        .scrollContentBackground(.hidden).background(Theme.canvas)
        .refreshable { await store.loadTrip(detail.trip.id) }
        .sheet(isPresented: $recording) { SettlementForm(detail: detail) }
    }

    private func label(_ net: Int) -> String {
        let amount = TravelFormat.money(abs(net), currency: detail.trip.currency_code)
        return net > 0 ? "Receives \(amount)" : net < 0 ? "Owes \(amount)" : "Settled"
    }
}

struct SettlementForm: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var recipientID = 0
    @State private var amount = ""
    @State private var method = "gcash"
    private var recipients: [TripMember] { (detail.trip.members ?? []).filter { $0.id != detail.current_member_id } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Recipient", selection: $recipientID) { ForEach(recipients) { Text($0.user.name).tag($0.id) } }
                    TextField("Amount (\(detail.trip.currency_code))", text: $amount).keyboardType(.decimalPad)
                    Picker("Payment method", selection: $method) {
                        Text("GCash").tag("gcash"); Text("Cash").tag("cash"); Text("Bank transfer").tag("bank_transfer"); Text("Other").tag("other")
                    }
                } footer: { Text("This records a repayment; it does not send money. The recipient confirms receipt.") }
            }
            .navigationTitle("Record money sent").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Record") {
                        guard let minor = TravelFormat.minor(amount) else { return }
                        Task {
                            let saved = await store.mutate { try await store.repository.createSettlement(tripID: detail.trip.id, input: SettlementInput(to_member_id: recipientID, amount_minor: minor, payment_method: method)) }
                            if saved { dismiss() }
                        }
                    }.disabled(recipientID == 0 || TravelFormat.minor(amount) == nil || store.busy)
                }
            }
            .onAppear { if recipientID == 0 { recipientID = recipients.first?.id ?? 0 } }
        }
    }
}

/// Travellers on the trip, and (for the organizer) an invitation link to share.
struct GroupView: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @State private var invitation: URL?
    @State private var viewing: TripMember?
    @State private var editMine = false

    private var me: TripMember? { detail.trip.members?.first { $0.id == detail.current_member_id } }

    var body: some View {
        List {
            if let me, me.user.phone == nil, me.user.emergency_contact_phone == nil {
                Section {
                    Button { editMine = true; viewing = me } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Add your number and emergency contact").font(.subheadline.bold())
                            Text("So your barkada can reach you, or someone close to you, if anything happens.").font(.caption).foregroundStyle(.secondary)
                        }
                    }.foregroundStyle(Color(red: 0.49, green: 0.20, blue: 0.14))
                }
            }
            Section {
                ForEach(detail.trip.members ?? []) { member in
                    Button { editMine = false; viewing = member } label: {
                        HStack(spacing: 12) {
                            AvatarView(name: member.user.name, url: member.user.avatar_url, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(member.user.name).foregroundStyle(.primary)
                                    if member.id == detail.current_member_id { Text("You").font(.caption).foregroundStyle(.secondary) }
                                }
                                Text(member.user.phone ?? "No number yet").font(.caption).foregroundStyle(Theme.moss)
                            }
                            Spacer()
                            if member.user.emergency_contact_phone != nil { Image(systemName: "cross.case.fill").foregroundStyle(Color(red: 0.69, green: 0.33, blue: 0.25)).accessibilityLabel("Has emergency contact") }
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                }
            } header: {
                HStack { Text("Travelers"); Spacer(); if let me { Button("My details") { editMine = true; viewing = me }.font(.caption.bold()).textCase(nil) } }
            } footer: { Text("Tap someone for their contact and emergency details.") }
            if detail.canPlan {
                Section {
                    if let invitation {
                        ShareLink(item: invitation, message: Text("Join our trip on BarkadaKasKas")) { Label("Share invitation link", systemImage: "square.and.arrow.up") }
                        Text(invitation.absoluteString).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                    } else {
                        Button("Invite friends", systemImage: "person.badge.plus") {
                            Task { await store.perform { invitation = try await store.repository.createInvitation(tripID: detail.trip.id) } }
                        }
                    }
                } footer: { Text("Invitation links are valid for 7 days.") }
            }
        }
        .scrollContentBackground(.hidden).background(Theme.canvas)
        .sheet(item: $viewing) { member in TravelerSheet(member: member, isMe: member.id == detail.current_member_id, startEditing: editMine) }
    }

    private func initials(_ name: String) -> String {
        name.split(separator: " ").prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased()
    }
}

/// A round profile picture, or initials when there is none.
struct AvatarView: View {
    let name: String; let url: String?; var size: CGFloat = 40
    var body: some View {
        ZStack {
            Circle().fill(Theme.sage)
            if let link = url.flatMap(URL.init(string:)) {
                AsyncImage(url: link) { image in image.resizable().scaledToFill() } placeholder: { ProgressView() }
            } else {
                Text(name.split(separator: " ").prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased())
                    .font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(Theme.forest)
            }
        }
        .frame(width: size, height: size).clipShape(Circle())
    }
}

/// A traveller's contact and emergency details; your own can be edited here, including your photo.
struct TravelerSheet: View {
    let member: TripMember; let isMe: Bool; let startEditing: Bool
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var phone = ""; @State private var other = ""
    @State private var emName = ""; @State private var emRelationship = ""; @State private var emPhone = ""
    @State private var photo: PhotosPickerItem?
    @State private var uploading = false

    /// The latest copy of this member after edits reload the trip.
    private var current: MemberUser { store.detail?.trip.members?.first { $0.id == member.id }?.user ?? member.user }
    private func tel(_ number: String) -> URL? { URL(string: "tel:" + number.filter { $0.isNumber || $0 == "+" }) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 8) {
                        ZStack(alignment: .bottomTrailing) {
                            AvatarView(name: current.name, url: current.avatar_url, size: 96)
                            if isMe {
                                PhotosPicker(selection: $photo, matching: .images) {
                                    Image(systemName: uploading ? "hourglass" : "camera.fill").font(.caption.bold()).foregroundStyle(.white)
                                        .frame(width: 32, height: 32).background(Theme.forest, in: Circle()).overlay(Circle().stroke(.white, lineWidth: 3))
                                }.disabled(uploading).accessibilityLabel("Change profile picture")
                            }
                        }
                        Text(current.name).font(.title3.bold())
                        if isMe && current.avatar_url != nil {
                            Button("Remove photo", role: .destructive) { Task { _ = await store.mutate { try await store.repository.removeAvatar() } } }.font(.caption)
                        }
                    }.frame(maxWidth: .infinity)
                }.listRowBackground(Color.clear)

                if editing {
                    Section("Contact") {
                        TextField("Mobile number", text: $phone).keyboardType(.phonePad).textContentType(.telephoneNumber)
                        TextField("Other contact (e.g. Messenger: @jamie)", text: $other)
                    }
                    Section("Emergency contact") {
                        TextField("Name", text: $emName)
                        TextField("Relationship (e.g. Mother)", text: $emRelationship)
                        TextField("Phone", text: $emPhone).keyboardType(.phonePad)
                    }
                } else {
                    Section("Contact") {
                        if let number = current.phone, let url = tel(number) { Link(destination: url) { Label(number, systemImage: "phone.fill") } }
                        if let handle = current.other_contact { Label(handle, systemImage: "message.fill") }
                        if current.phone == nil && current.other_contact == nil { Text(isMe ? "Add a number so your barkada can reach you." : "No contact details yet.").foregroundStyle(.secondary) }
                    }
                    Section("Emergency contact") {
                        if current.emergency_contact_name != nil || current.emergency_contact_phone != nil {
                            Text([current.emergency_contact_name, current.emergency_contact_relationship].compactMap { $0 }.joined(separator: " · ")).font(.headline)
                            if let number = current.emergency_contact_phone, let url = tel(number) {
                                Link(destination: url) { Label("Call \(number)", systemImage: "phone.fill") }.foregroundStyle(.red)
                            }
                        } else {
                            Text(isMe ? "Add someone the group can call if something happens to you." : "Not added yet.").foregroundStyle(.secondary)
                        }
                    }
                    if isMe { Section { Button("Edit my details", systemImage: "pencil") { startEdit() } } footer: { Text("Only people on your trips can see these details.") } }
                }
            }
            .navigationTitle(isMe ? "Your details" : "Traveler").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(editing ? "Cancel" : "Done") { if editing { editing = false } else { dismiss() } } }
                if editing {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            Task {
                                let clean: (String) -> String? = { value in let t = value.trimmingCharacters(in: .whitespaces); return t.isEmpty ? nil : t }
                                let input = ProfileInput(phone: clean(phone), other_contact: clean(other), emergency_contact_name: clean(emName), emergency_contact_relationship: clean(emRelationship), emergency_contact_phone: clean(emPhone))
                                if await store.mutate({ try await store.repository.updateProfile(input) }) { editing = false }
                            }
                        }.disabled(store.busy)
                    }
                }
            }
            .onAppear { if isMe && startEditing { startEdit() } }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    uploading = true
                    defer { uploading = false; photo = nil }
                    guard let data = try? await item.loadTransferable(type: Data.self), let jpeg = CoverPhoto.jpeg(from: data, maxSide: 800) else { store.error = "That photo couldn’t be opened."; return }
                    _ = await store.mutate { try await store.repository.uploadAvatar(jpeg: jpeg) }
                }
            }
        }
    }

    private func startEdit() {
        phone = current.phone ?? ""; other = current.other_contact ?? ""
        emName = current.emergency_contact_name ?? ""; emRelationship = current.emergency_contact_relationship ?? ""; emPhone = current.emergency_contact_phone ?? ""
        editing = true
    }
}
