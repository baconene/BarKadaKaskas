import SwiftUI
import CoreLocation
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
    @Environment(\.openURL) private var openURL
    @State private var form = false
    @State private var editing: ItineraryStop?
    @State private var selectedDay: String?
    @State private var guesses: [Int: CLLocationCoordinate2D] = [:]
    private var trip: Trip { detail.trip }
    private var days: [ItineraryLogic.Day] { ItineraryLogic.days(trip.itinerary_items ?? [], timezone: trip.timezone) }
    private var day: ItineraryLogic.Day? {
        let all = days
        let today = TravelFormat.date(Date(), timezone: trip.timezone)
        return all.first { $0.key == (selectedDay ?? today) } ?? all.first
    }
    private func pins(_ day: ItineraryLogic.Day) -> [MapStop] {
        day.stops.enumerated().compactMap { index, stop in
            guard let point = ItineraryLogic.coordinate(stop) ?? guesses[stop.id] else { return nil }
            return MapStop(id: stop.id, number: index + 1, title: stop.title, coordinate: point, approximate: ItineraryLogic.coordinate(stop) == nil)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if days.isEmpty {
                    VStack(spacing: 14) {
                        EmptyMessage(title: "Your days are open", subtitle: "Add your arrival point, then the places you want to visit.", icon: "map")
                        if detail.is_organizer { Button { editing = nil; form = true } label: { Label("Add the first stop", systemImage: "plus").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButtonStyle()) }
                    }.cardStyle()
                } else if let day {
                    dayPicker(current: day.key)
                    mapCard(day)
                    VStack(spacing: 0) {
                        ForEach(Array(day.stops.enumerated()), id: \.element.id) { index, stop in
                            TimelineRow(stop: stop, previous: index > 0 ? day.stops[index - 1] : nil, isLast: index == day.stops.count - 1, trip: trip, canEdit: detail.is_organizer, guesses: guesses) { editing = stop; form = true }
                        }
                    }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
        }
        .background(Theme.canvas)
        .refreshable { await store.loadTrip(trip.id) }
        .sensoryFeedback(.selection, trigger: selectedDay)
        .sheet(isPresented: $form) { StopForm(detail: detail, editing: editing) }
        .task(id: day?.key) { await locate(day) }
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Itinerary").font(.largeTitle.bold())
                let count = trip.itinerary_items?.count ?? 0
                Text("\(days.count) day\(days.count == 1 ? "" : "s") · \(count) activit\(count == 1 ? "y" : "ies")").foregroundStyle(.secondary)
                if let total = ItineraryLogic.totalCost(trip.itinerary_items ?? []), let range = ItineraryLogic.costRange(total.0, total.1, currency: trip.currency_code) {
                    Label("\(range) per person (estimate)", systemImage: "banknote").font(.caption.bold()).foregroundStyle(Theme.forest)
                        .padding(.horizontal, 10).padding(.vertical, 5).background(Theme.sage, in: Capsule()).padding(.top, 4)
                }
            }
            Spacer()
            if detail.is_organizer {
                Button { editing = nil; form = true } label: { Label("Add", systemImage: "plus").font(.subheadline.bold()) }
                    .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(Theme.forest)
            }
        }
    }

    private func dayPicker(current: String) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(days) { item in
                    let active = item.key == current
                    Button { withAnimation(.snappy) { selectedDay = item.key } } label: {
                        VStack(spacing: 3) {
                            Text(ItineraryLogic.weekday(item.key)).font(.caption2.bold()).foregroundStyle(active ? Theme.sage : Color.secondary)
                            Text(ItineraryLogic.monthDay(item.key)).font(.subheadline.bold()).foregroundStyle(active ? Color.white : Color.primary)
                            Text("\(item.stops.count)").font(.caption).foregroundStyle(active ? Color.white : Theme.moss)
                        }
                        .frame(width: 74).padding(.vertical, 12)
                        .background(active ? Theme.forest : Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(active ? Color.clear : Theme.line, lineWidth: 1))
                    }
                    .buttonStyle(PressableStyle())
                    .accessibilityLabel("\(ItineraryLogic.dayLabel(item.key)), \(item.stops.count) activities")
                    .accessibilityAddTraits(active ? .isSelected : [])
                }
            }.padding(.vertical, 2)
        }
    }

    private func mapCard(_ day: ItineraryLogic.Day) -> some View {
        let markers = pins(day)
        let located = day.stops.filter { !($0.location ?? "").isEmpty }.count
        return VStack(spacing: 0) {
            ZStack {
                if markers.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: located > 0 ? "location.magnifyingglass" : "mappin.slash").font(.title2).foregroundStyle(.secondary)
                        Text(located > 0 ? "Finding places on the map…" : "Add locations to see this day on the map").font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }.padding()
                } else {
                    TripMapView(stops: markers)
                }
            }
            .frame(height: 250).frame(maxWidth: .infinity).background(Theme.sage.opacity(0.5))
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Route for \(ItineraryLogic.dayLabel(day.key))").font(.subheadline.bold())
                    Text(located > markers.count ? "\(markers.count) on the map · \(located - markers.count) still locating" : "\(day.stops.count) stops · tap a pin for its name").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if let url = ItineraryLogic.dayDirectionsURL(day.stops, guesses: guesses) {
                    Button { openURL(url) } label: { Label("Navigate", systemImage: "location.north.line.fill").font(.subheadline.bold()) }
                        .buttonStyle(.borderedProminent).tint(Theme.forest)
                }
            }.padding(14)
        }
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }

    /// Typed addresses have no pin; look them up on the device so they still show on the map.
    private func locate(_ day: ItineraryLogic.Day?) async {
        guard let day else { return }
        for stop in day.stops where ItineraryLogic.coordinate(stop) == nil && guesses[stop.id] == nil {
            guard let location = stop.location, !location.isEmpty else { continue }
            if let found = await PlaceLookup.shared.coordinate(for: PlaceLookup.query(location, destination: trip.destination)) { guesses[stop.id] = found }
        }
    }
}

private struct TimelineRow: View {
    let stop: ItineraryStop; let previous: ItineraryStop?; let isLast: Bool; let trip: Trip; let canEdit: Bool
    let guesses: [Int: CLLocationCoordinate2D]
    let edit: () -> Void
    @Environment(\.openURL) private var openURL
    @State private var expanded = false

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .trailing, spacing: 2) {
                Text(ItineraryLogic.clock(stop.starts_at, timezone: trip.timezone)).font(.caption.bold())
                Text(ItineraryLogic.clock(stop.ends_at, timezone: trip.timezone)).font(.caption2).foregroundStyle(.secondary)
            }.frame(width: 58, alignment: .trailing).padding(.top, 14)
            Image(systemName: ItineraryLogic.symbol(for: stop.title)).font(.caption).foregroundStyle(Theme.forest)
                .frame(width: 30, height: 30).background(Theme.sage, in: Circle()).padding(.top, 10)
            card.padding(.bottom, 12)
        }
        // The rail is a background so it stretches to the row's full height.
        .background(alignment: .topLeading) {
            if !isLast { Rectangle().fill(Theme.line).frame(width: 2).frame(maxHeight: .infinity).padding(.top, 40).offset(x: 58 + 10 + 14) }
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .top) {
                Text(stop.title).font(.subheadline.bold()).frame(maxWidth: .infinity, alignment: .leading)
                if canEdit { Button(action: edit) { Image(systemName: "pencil").font(.caption.bold()).frame(width: 30, height: 30).background(Theme.sage.opacity(0.6), in: Circle()) }.accessibilityLabel("Edit \(stop.title)") }
            }
            if let location = stop.location, !location.isEmpty {
                Button { if let url = ItineraryLogic.searchURL(stop, guess: guesses[stop.id]) { openURL(url) } } label: {
                    Label(location, systemImage: "mappin").font(.caption).foregroundStyle(Theme.forest).lineLimit(expanded ? 3 : 1).multilineTextAlignment(.leading)
                }
            }
            HStack(spacing: 6) {
                if let cost = ItineraryLogic.costRange(stop.cost_min_minor, stop.cost_max_minor, currency: trip.currency_code) { chip(cost, "banknote", strong: true) }
                if let previous {
                    if ItineraryLogic.samePlace(previous, stop) { chip("Same spot", "scope") }
                    else { chip(TravelFormat.transport(stop.transport_mode).components(separatedBy: " /").first ?? "", ItineraryLogic.transportSymbol(stop.transport_mode)) }
                } else { chip("Start", "flag.fill") }
            }
            if expanded {
                if let notes = stop.description, !notes.isEmpty { Text(notes).font(.caption).foregroundStyle(.secondary) }
                if let note = stop.cost_note, !note.isEmpty { Text(note).font(.caption).foregroundStyle(.secondary) }
                HStack(spacing: 8) {
                    if let url = ItineraryLogic.searchURL(stop, guess: guesses[stop.id]), stop.location?.isEmpty == false {
                        Button { openURL(url) } label: { Label("Map", systemImage: "map") }.buttonStyle(.bordered)
                    }
                    if let previous, !ItineraryLogic.samePlace(previous, stop), let url = ItineraryLogic.directionsURL(from: previous, to: stop, guesses: guesses) {
                        Button { openURL(url) } label: { Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond") }.buttonStyle(.bordered)
                    }
                }.font(.caption.bold()).tint(Theme.forest)
            } else if (stop.description ?? "").isEmpty == false || stop.location?.isEmpty == false {
                Text("Tap for details").font(.caption2).foregroundStyle(Theme.moss)
            }
        }
        .cardStyle(padding: 14)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.snappy) { expanded.toggle() } }
    }

    private func chip(_ text: String, _ symbol: String, strong: Bool = false) -> some View {
        Label(text, systemImage: symbol).font(.caption2.bold())
            .foregroundStyle(strong ? Theme.forest : Color.secondary)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background((strong ? Theme.sage : Color.gray.opacity(0.12)), in: Capsule())
    }
}

struct StopForm: View {
    let detail: TripDetail; let editing: ItineraryStop?
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""; @State private var location = ""; @State private var notes = ""
    @State private var transport = ""; @State private var start = Date(); @State private var end = Date()
    private var keepsPin: Bool { editing.map { ItineraryLogic.coordinate($0) != nil && ($0.location ?? "").trimmingCharacters(in: .whitespaces) == location.trimmingCharacters(in: .whitespaces) } ?? false }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label { TextField("What’s happening?", text: $title) } icon: { Image(systemName: ItineraryLogic.symbol(for: title)).foregroundStyle(Theme.forest) }
                    Label { TextField("Full address / place", text: $location) } icon: { Image(systemName: "mappin").foregroundStyle(Theme.forest) }
                    TextField("Notes", text: $notes, axis: .vertical)
                } header: { Text("Activity") } footer: { Text(keepsPin ? "Pinned on the map." : "We’ll find this place on the map for you.") }
                Section("Schedule in \(detail.trip.timezone)") {
                    DatePicker("Starts", selection: $start)
                    DatePicker("Ends", selection: $end, in: start...)
                }.environment(\.timeZone, TimeZone(identifier: detail.trip.timezone) ?? .gmt)
                Section {
                    Picker("Transport", selection: $transport) { ForEach(TravelFormat.transports, id: \.0) { Text($0.1).tag($0.0) } }
                } header: { Text("Getting here from the previous stop") } footer: { Text("The first activity of the day is your starting point.") }
            }
            .navigationTitle(editing == nil ? "New activity" : "Edit activity").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") {
                    Task {
                        // Keep an existing pin when the address is unchanged; otherwise look up the new address.
                        var pin: CLLocationCoordinate2D?
                        if let editing, keepsPin { pin = ItineraryLogic.coordinate(editing) }
                        else if !location.trimmingCharacters(in: .whitespaces).isEmpty { pin = await PlaceLookup.shared.coordinate(for: PlaceLookup.query(location, destination: detail.trip.destination)) }
                        let input = StopInput(title: title, location: location, description: notes, starts_at: TravelFormat.date(start, timezone: detail.trip.timezone, includeTime: true), ends_at: TravelFormat.date(max(end, start), timezone: detail.trip.timezone, includeTime: true), transport_mode: transport.isEmpty ? nil : transport, latitude: pin?.latitude, longitude: pin?.longitude)
                        let saved = await store.mutate { try await store.repository.saveStop(tripID: detail.trip.id, stopID: editing?.id, input: input) }
                        if saved { dismiss() }
                    }
                }.disabled(title.isEmpty || store.busy) }
            }
            .onAppear {
                if let item = editing { title = item.title; location = item.location ?? ""; notes = item.description ?? ""; transport = item.transport_mode ?? ""; start = TravelFormat.parse(item.starts_at); end = TravelFormat.parse(item.ends_at) }
                else { start = TravelFormat.parse(detail.trip.starts_on).addingTimeInterval(9 * 3600); end = start.addingTimeInterval(3600) }
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
