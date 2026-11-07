import SwiftUI

/// Record a shared bill, split equally between the chosen travellers. The server allocates leftover centavos.
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

    private var members: [TripMember] { detail.trip.members ?? [] }
    private var totalMinor: Int? { TravelFormat.minor(amount) }

    var body: some View {
        NavigationStack {
            Form {
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
            .navigationTitle("New expense").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(title.isEmpty || categoryID == 0 || totalMinor == nil || sharedBy.isEmpty || store.busy)
                }
            }
            .onAppear {
                if categoryID == 0 { categoryID = detail.categories.first?.id ?? 0 }
                if sharedBy.isEmpty { sharedBy = Set(members.map(\.id)) }
            }
        }
    }

    private func save() async {
        guard let total = totalMinor else { return }
        let input = ExpenseInput(
            title: title, merchant: merchant, category_id: categoryID,
            incurred_at: TravelFormat.date(date, timezone: detail.trip.timezone),
            total_minor: total, split_method: "equal",
            payments: payerID == 0 ? [] : [.init(trip_member_id: payerID, amount_minor: total)],
            allocations: sharedBy.sorted().map { .init(trip_member_id: $0, weight: 1) }
        )
        let saved = await store.mutate { try await store.repository.createExpense(tripID: detail.trip.id, input: input) }
        if saved { dismiss() }
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

    var body: some View {
        List {
            Section("Travelers") {
                ForEach(detail.trip.members ?? []) { member in
                    HStack(spacing: 12) {
                        Text(initials(member.user.name))
                            .font(.caption.bold()).frame(width: 36, height: 36)
                            .background(Theme.forest.opacity(0.12), in: Circle())
                        Text(member.user.name)
                        if member.id == detail.current_member_id { Text("You").font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
            if detail.canPlan {
                Section {
                    if let invitation {
                        ShareLink(item: invitation, message: Text("Join our trip on BarKadaKaskas")) { Label("Share invitation link", systemImage: "square.and.arrow.up") }
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
    }

    private func initials(_ name: String) -> String {
        name.split(separator: " ").prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased()
    }
}
