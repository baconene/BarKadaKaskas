import SwiftUI
import UniformTypeIdentifiers

struct TripDetailView: View {
    let tripID: Int
    @EnvironmentObject var store: AppStore
    @State private var tab = 0
    var body: some View {
        Group {
            if let detail = store.detail, detail.trip.id == tripID {
                TabView(selection: $tab) {
                    ItineraryView(detail: detail).tabItem { Label("Itinerary", systemImage: "map") }.tag(0)
                    ExpensesView(detail: detail).tabItem { Label("Expenses", systemImage: "receipt") }.tag(1)
                    BalancesView(detail: detail).tabItem { Label("Balances", systemImage: "arrow.left.arrow.right") }.tag(2)
                    GroupView(detail: detail).tabItem { Label("Group", systemImage: "person.2") }.tag(3)
                }
                .navigationTitle(detail.trip.name).navigationBarTitleDisplayMode(.inline)
            } else if store.busy { ProgressView("Loading your trip…") }
            else {
                VStack { EmptyMessage(title: "Couldn’t load this trip", subtitle: "Check your connection and try again.", icon: "wifi.exclamationmark"); Button("Try again") { Task { await store.loadTrip(tripID) } } }
            }
        }.task { await store.loadTrip(tripID) }
    }
}

struct ItineraryView: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @State private var form = false
    @State private var editing: ItineraryStop?
    private var stops: [ItineraryStop] { (detail.trip.itinerary_items ?? []).sorted { $0.starts_at == $1.starts_at ? $0.id < $1.id : $0.starts_at < $1.starts_at } }
    var body: some View {
        List {
            Section {
                Text(detail.trip.destination).font(.title2.bold())
                Text("Times in \(detail.trip.timezone)").font(.caption).foregroundStyle(.secondary)
                if detail.is_organizer { Button("Add an activity", systemImage: "plus") { editing = nil; form = true } }
            }
            if stops.isEmpty { EmptyMessage(title: "Your days are open", subtitle: "Add your arrival point, then the places you want to visit.", icon: "map") }
            ForEach(Array(stops.enumerated()), id: \.element.id) { index, stop in
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("STOP \(index + 1)").font(.caption.weight(.semibold)).foregroundStyle(Theme.forest)
                        Text(stop.title).font(.headline)
                        Text(TravelFormat.display(stop.starts_at, timezone: detail.trip.timezone)).font(.subheadline)
                        Text("Until \(TravelFormat.display(stop.ends_at, timezone: detail.trip.timezone))").font(.caption).foregroundStyle(.secondary)
                        if let location = stop.location, let url = TravelFormat.maps(location), !location.isEmpty { Link(destination: url) { Label(location, systemImage: "mappin.and.ellipse") } }
                        Label(index == 0 ? "Starting point" : TravelFormat.transport(stop.transport_mode), systemImage: "figure.walk")
                        if let description = stop.description, !description.isEmpty { Text(description).font(.subheadline).foregroundStyle(.secondary) }
                        if let cost = stop.cost_min_minor { Text("Estimated per person: \(TravelFormat.money(cost, currency: detail.trip.currency_code))\(stop.cost_max_minor.map { " – " + TravelFormat.money($0, currency: detail.trip.currency_code) } ?? "")").font(.caption) }
                        if detail.is_organizer { Button("Edit activity") { editing = stop; form = true } }
                    }.padding(.vertical, 5)
                }
            }
        }
        .scrollContentBackground(.hidden).background(Theme.canvas)
        .refreshable { await store.loadTrip(detail.trip.id) }
        .sheet(isPresented: $form) { StopForm(detail: detail, editing: editing) }
    }
}

struct StopForm: View {
    let detail: TripDetail; let editing: ItineraryStop?
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""; @State private var location = ""; @State private var notes = ""
    @State private var transport = ""; @State private var start = Date(); @State private var end = Date()
    var body: some View {
        NavigationStack {
            Form {
                Section("Activity") { TextField("What’s happening?", text: $title); TextField("Full address / place", text: $location); TextField("Notes", text: $notes, axis: .vertical) }
                Section("Schedule in \(detail.trip.timezone)") {
                    DatePicker("Starts", selection: $start)
                    DatePicker("Ends", selection: $end, in: start...)
                }.environment(\.timeZone, TimeZone(identifier: detail.trip.timezone) ?? .gmt)
                Section("From the previous activity") {
                    Picker("Transport", selection: $transport) { ForEach(TravelFormat.transports, id: \.0) { Text($0.1).tag($0.0) } }
                    Text("The first activity is your starting point. Use a full address to open the right place in Google Maps.").font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(editing == nil ? "New activity" : "Edit activity").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") {
                    Task {
                        let saved = await store.mutate { try await store.repository.saveStop(tripID: detail.trip.id, stopID: editing?.id, input: StopInput(title: title, location: location, description: notes, starts_at: TravelFormat.date(start, timezone: detail.trip.timezone, includeTime: true), ends_at: TravelFormat.date(max(end, start), timezone: detail.trip.timezone, includeTime: true), transport_mode: transport.isEmpty ? nil : transport)) }
                        if saved { dismiss() }
                    }
                }.disabled(title.isEmpty || store.busy) }
            }
            .onAppear {
                if let item = editing { title = item.title; location = item.location ?? ""; notes = item.description ?? ""; transport = item.transport_mode ?? ""; start = TravelFormat.parse(item.starts_at); end = TravelFormat.parse(item.ends_at) }
                else { start = TravelFormat.parse(detail.trip.starts_on); end = start.addingTimeInterval(3600) }
            }
        }
    }
}

struct ExpensesView: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @State private var adding = false
    var body: some View {
        List {
            Section { Button("Record an expense", systemImage: "plus") { adding = true } }
            if (detail.trip.expenses ?? []).isEmpty { EmptyMessage(title: "No shared bills yet", subtitle: "Record a bill and choose who shares it.", icon: "receipt") }
            ForEach(detail.trip.expenses ?? []) { expense in
                NavigationLink { ExpenseDetailView(tripID: detail.trip.id, expenseID: expense.id) } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(expense.title).font(.headline); Spacer(); Text(TravelFormat.money(expense.total_minor, currency: detail.trip.currency_code)).fontWeight(.semibold) }
                        Text("\(expense.category?.name ?? "Expense") · \(expense.merchant ?? "")").font(.caption).foregroundStyle(.secondary)
                        Text(expense.status.capitalized).font(.caption.bold()).padding(.horizontal, 8).padding(.vertical, 4).background(Theme.forest.opacity(0.10), in: Capsule())
                    }.padding(.vertical, 5)
                }
            }
        }
        .scrollContentBackground(.hidden).background(Theme.canvas)
        .refreshable { await store.loadTrip(detail.trip.id) }
        .sheet(isPresented: $adding) { ExpenseForm(detail: detail) }
    }
}

struct ExpenseDetailView: View {
    let tripID: Int; let expenseID: Int
    @EnvironmentObject var store: AppStore
    @State private var confirmation: String?
    @State private var share: ExpenseShare?
    @State private var cancellationReason = ""
    @State private var voidPayments = false
    @State private var importing = false
    var body: some View {
        Group {
            if let detail = store.detail, let expense = detail.trip.expenses?.first(where: { $0.id == expenseID }) {
                List {
                    Section {
                        Text(TravelFormat.money(expense.total_minor, currency: detail.trip.currency_code)).font(.largeTitle.bold()).foregroundStyle(Theme.forest)
                        Text("\(expense.category?.name ?? "Expense") · \(expense.merchant ?? "")")
                        LabeledContent("Status", value: expense.status.capitalized)
                        LabeledContent("Split", value: expense.split_method.capitalized)
                    }
                    Section("Assigned shares") {
                        ForEach(detail.expense_shares[String(expense.id)] ?? []) { item in
                            VStack(alignment: .leading, spacing: 7) {
                                HStack { Text(detail.name(item.member_id)); Spacer(); Text(TravelFormat.money(item.share_minor, currency: detail.trip.currency_code)) }
                                if item.remaining_minor <= 0 { Label("Paid / covered", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(Theme.forest) }
                                else {
                                    Text("Remaining: \(TravelFormat.money(item.remaining_minor, currency: detail.trip.currency_code))").font(.caption).foregroundStyle(.secondary)
                                    if expense.status != "cancelled" && (detail.is_organizer || (expense.payments ?? []).contains(where: { $0.trip_member_id == detail.current_member_id })) {
                                        Button("Mark as paid") { share = item }
                                    }
                                }
                            }
                        }
                    }
                    Section("Paid to merchant") {
                        ForEach(Array((expense.payments ?? []).enumerated()), id: \.offset) { _, payment in LabeledContent(detail.name(payment.trip_member_id), value: TravelFormat.money(payment.amount_minor, currency: detail.trip.currency_code)) }
                    }
                    Section("Receipts") {
                        ForEach(expense.receipts ?? []) { receipt in Text("Receipt #\(receipt.id) · \(receipt.processing_status)") }
                        if expense.status != "closed" && expense.status != "cancelled" { Button("Attach receipt", systemImage: "paperclip") { importing = true } }
                    }
                    if detail.is_organizer && expense.status != "closed" && expense.status != "cancelled" {
                        Section("Expense actions") {
                            if expense.status == "open" { Button("Mark merchant bill completed") { confirmation = "completed" } }
                            if expense.status == "completed" { Button("Close expense") { confirmation = "closed" } }
                            TextField("Reason for cancelling", text: $cancellationReason)
                            if !(expense.payments ?? []).isEmpty { Toggle("Void merchant payments when cancelling", isOn: $voidPayments) }
                            Button("Cancel expense", role: .destructive) { confirmation = "cancelled" }.disabled(cancellationReason.isEmpty)
                        }
                    }
                }
                .navigationTitle(expense.title).navigationBarTitleDisplayMode(.inline)
                .confirmationDialog("Confirm expense change", isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } })) {
                    Button("Confirm") {
                        guard let status = confirmation else { return }; confirmation = nil
                        Task { await store.mutate { try await store.repository.changeExpenseStatus(tripID: tripID, expenseID: expenseID, version: expense.version, status: status, reason: cancellationReason, voidPayments: voidPayments) } }
                    }
                } message: { Text(confirmation == "closed" ? "Closing locks this expense. Reimbursements can still be recorded." : "This updates the bill’s recorded status. It does not transfer money.") }
                .confirmationDialog("Confirm payment received", isPresented: Binding(get: { share != nil }, set: { if !$0 { share = nil } })) {
                    Button("Mark as paid") {
                        guard let item = share else { return }; share = nil
                        Task { await store.mutate { try await store.repository.markSharePaid(tripID: tripID, expenseID: expenseID, memberID: item.member_id, version: expense.version) } }
                    }
                } message: { Text("Only confirm money already received. Existing general reimbursements may need reconciliation on the web before this can be recorded.") }
                .fileImporter(isPresented: $importing, allowedContentTypes: [.jpeg, .png, .pdf]) { result in
                    Task {
                        await store.mutate {
                            let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                            let data = try Data(contentsOf: url)
                            guard data.count <= 10 * 1024 * 1024 else { throw MobileError.validation("Receipt must be 10 MB or smaller.") }
                            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                            try await store.repository.uploadReceipt(tripID: tripID, expenseID: expenseID, data: data, fileName: url.lastPathComponent, mimeType: mime)
                        }
                    }
                }
            } else { ProgressView() }
        }
    }
}
