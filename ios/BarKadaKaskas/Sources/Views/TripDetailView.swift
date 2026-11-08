import SwiftUI
import PhotosUI
import UIKit
import CoreLocation
import UniformTypeIdentifiers

struct TripDetailView: View {
    let tripID: Int
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0
    @State private var cancelling = false
    @State private var editingTrip = false
    @State private var confirmHide = false

    /// Hiding removes the trip from the list, so go back to it afterwards.
    private func lifecycle(hideAfter: Bool, _ action: @escaping () async throws -> Void) {
        Task {
            let done = await store.mutate { try await action(); store.trips = try await store.repository.trips() }
            if done && hideAfter { dismiss() }
        }
    }

    var body: some View {
        Group {
            if let detail = store.detail, detail.trip.id == tripID {
                VStack(spacing: 0) {
                if detail.trip.isCancelled { CancelledBanner(detail: detail) { lifecycle(hideAfter: false) { try await store.repository.reopenTrip(tripID: tripID) } } }
                TabView(selection: $tab) {
                    ItineraryView(detail: detail).tabItem { Label("Itinerary", systemImage: "map") }.tag(0)
                    ExpensesView(detail: detail).tabItem { Label("Expenses", systemImage: "receipt") }.tag(1)
                    PhotosView(detail: detail).tabItem { Label("Photos", systemImage: "photo.on.rectangle.angled") }.tag(4)
                    BalancesView(detail: detail).tabItem { Label("Balances", systemImage: "arrow.left.arrow.right") }.tag(2)
                    GroupView(detail: detail).tabItem { Label("Group", systemImage: "person.2") }.tag(3)
                }
                }
                .navigationTitle(detail.trip.name).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if detail.is_organizer {
                        ToolbarItem(placement: .topBarTrailing) {
                            Menu {
                                if !detail.trip.isCancelled {
                                    Button("Edit trip & cover photo", systemImage: "pencil") { editingTrip = true }
                                    Button("Cancel trip…", systemImage: "xmark.circle", role: .destructive) { cancelling = true }
                                } else {
                                    Button("Reopen trip", systemImage: "arrow.uturn.backward.circle") { lifecycle(hideAfter: false) { try await store.repository.reopenTrip(tripID: tripID) } }
                                    if detail.trip.hidden_at == nil { Button("Hide from trip lists", systemImage: "eye.slash") { confirmHide = true } }
                                    else { Button("Show in trip lists", systemImage: "eye") { lifecycle(hideAfter: false) { try await store.repository.setTripHidden(tripID: tripID, hidden: false) } } }
                                }
                            } label: { Image(systemName: "ellipsis.circle") }
                            .accessibilityLabel("Trip options")
                        }
                    }
                }
                .sheet(isPresented: $editingTrip) { EditTripView(detail: detail) }
                .sheet(isPresented: $cancelling) {
                    CancelTripSheet(tripName: detail.trip.name) { reason, hide in
                        lifecycle(hideAfter: hide) { try await store.repository.cancelTrip(tripID: tripID, reason: reason, hide: hide) }
                    }
                }
                .confirmationDialog("Hide this trip?", isPresented: $confirmHide, titleVisibility: .visible) {
                    Button("Hide trip") { lifecycle(hideAfter: true) { try await store.repository.setTripHidden(tripID: tripID, hidden: true) } }
                } message: { Text("It won’t show in anyone’s trip list. You can show it again from this menu.") }
            } else if store.busy { ProgressView("Loading your trip…") }
            else {
                VStack { EmptyMessage(title: "Couldn’t load this trip", subtitle: "Check your connection and try again.", icon: "wifi.exclamationmark"); Button("Try again") { Task { await store.loadTrip(tripID) } } }
            }
        }.task { await store.loadTrip(tripID) }
    }
}

/// Organizer editor for the trip details and cover photo. A picked photo uploads straight away.
private struct EditTripView: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""; @State private var destination = ""; @State private var about = ""
    @State private var start = Date(); @State private var end = Date()
    @State private var currency = "PHP"; @State private var timezone = "Asia/Manila"
    @State private var photo: PhotosPickerItem?
    @State private var uploading = false
    private var trip: Trip { store.detail?.trip ?? detail.trip }
    private var currencyLocked: Bool { !(detail.trip.expenses ?? []).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ZStack(alignment: .bottomTrailing) {
                        Group {
                            if let url = trip.cover_url.flatMap(URL.init(string:)) {
                                AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { ProgressView() }
                            } else {
                                VStack(spacing: 6) {
                                    Image(systemName: "photo.badge.plus").font(.title).foregroundStyle(Theme.forest)
                                    Text("Add a cover photo").font(.subheadline.bold()).foregroundStyle(Theme.forest)
                                    Text("Shown on everyone’s trip card").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity).frame(height: 170).background(Theme.sage).clipped()
                        .overlay { if uploading { ZStack { Color.black.opacity(0.35); ProgressView().tint(.white) } } }
                        HStack(spacing: 8) {
                            if trip.cover_url != nil {
                                Button(role: .destructive) { Task { await store.mutate { try await store.repository.removeCover(tripID: trip.id); store.trips = try await store.repository.trips() } } } label: { Image(systemName: "trash") }
                                    .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.red)
                                    .accessibilityLabel("Remove cover photo")
                            }
                            PhotosPicker(selection: $photo, matching: .images) { Label(trip.cover_url == nil ? "Upload" : "Change", systemImage: "camera.fill") }
                                .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(Theme.forest)
                        }.padding(10).font(.subheadline.bold())
                    }
                    .listRowInsets(EdgeInsets())
                }
                Section("The adventure") {
                    TextField("Trip name", text: $name)
                    TextField("Destination", text: $destination)
                    TextField("About this trip", text: $about, axis: .vertical)
                }
                Section {
                    DatePicker("Starts", selection: $start, displayedComponents: .date)
                    DatePicker("Ends", selection: $end, in: start..., displayedComponents: .date)
                    Picker("Currency", selection: $currency) { ForEach(["PHP", "USD", "EUR", "GBP", "SGD", "AUD", "CAD"], id: \.self) { Text($0) } }.disabled(currencyLocked)
                    TextField("Time zone", text: $timezone).textInputAutocapitalization(.never).autocorrectionDisabled()
                } header: { Text("Dates & currency") } footer: { if currencyLocked { Text("The currency is locked because expenses have been recorded.") } }
            }
            .navigationTitle("Edit trip").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            let input = TripInput(name: name, destination: destination, description: about, starts_on: TravelFormat.date(start), ends_on: TravelFormat.date(max(start, end)), timezone: timezone, currency_code: currency)
                            let saved = await store.mutate {
                                guard TimeZone(identifier: timezone) != nil else { throw MobileError.validation("Enter a valid time zone, such as Asia/Manila.") }
                                try await store.repository.updateTrip(tripID: trip.id, input: input)
                                store.trips = try await store.repository.trips()
                            }
                            if saved { dismiss() }
                        }
                    }.disabled(name.isEmpty || destination.isEmpty || store.busy || uploading)
                }
            }
            .onAppear {
                name = trip.name; destination = trip.destination; about = trip.description ?? ""
                start = TravelFormat.parse(trip.starts_on); end = TravelFormat.parse(trip.ends_on)
                currency = trip.currency_code; timezone = trip.timezone
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    uploading = true
                    defer { uploading = false; photo = nil }
                    guard let data = try? await item.loadTransferable(type: Data.self), let jpeg = CoverPhoto.jpeg(from: data) else {
                        store.error = "That photo couldn’t be opened. Try another one."; return
                    }
                    _ = await store.mutate { try await store.repository.uploadCover(tripID: trip.id, jpeg: jpeg); store.trips = try await store.repository.trips() }
                }
            }
        }
    }
}

/// Downscales a picked photo (longest side 1600 pt) and re-encodes it as JPEG before upload.
enum CoverPhoto {
    static func jpeg(from data: Data, maxSide: CGFloat = 1600) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let longest = max(image.size.width, image.size.height)
        guard longest > maxSide else { return image.jpegData(compressionQuality: 0.85) }
        let scale = maxSide / longest
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default(); format.scale = 1
        // Drawing also bakes in the photo's orientation.
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return resized.jpegData(compressionQuality: 0.85)
    }
}

private struct CancelledBanner: View {
    let detail: TripDetail
    let reopen: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(detail.trip.hidden_at == nil ? "This trip was cancelled" : "Cancelled · hidden from trip lists", systemImage: "xmark.octagon.fill").font(.subheadline.bold())
            if let reason = detail.trip.cancellation_reason, !reason.isEmpty { Text("“\(reason)”").font(.subheadline) }
            Text("The plan and new expenses are locked. You can still record repayments and settle up.").font(.caption)
            if detail.is_organizer { Button("Reopen trip", systemImage: "arrow.uturn.backward", action: reopen).font(.caption.bold()).buttonStyle(.bordered).tint(Color(red: 0.49, green: 0.20, blue: 0.14)) }
        }
        .foregroundStyle(Color(red: 0.49, green: 0.20, blue: 0.14))
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 1.0, green: 0.96, blue: 0.94))
    }
}

/// Organizer confirmation for cancelling a trip, with the option to hide it from trip lists.
private struct CancelTripSheet: View {
    let tripName: String
    let confirm: (String, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""
    @State private var hide = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Reason (optional)", text: $reason, axis: .vertical).lineLimit(2...4)
                } footer: { Text("The plan and new expenses are locked. Everyone can still settle up, and you can reopen the trip later.") }
                Section {
                    Toggle(isOn: $hide) { Label { VStack(alignment: .leading) { Text("Hide this trip"); Text("It won’t show in anyone’s trip list.").font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: "eye.slash") } }
                        .tint(Theme.forest)
                }
                Section {
                    Button(role: .destructive) { dismiss(); confirm(reason, hide) } label: { Text(hide ? "Cancel & hide trip" : "Cancel trip").frame(maxWidth: .infinity).fontWeight(.semibold) }
                }
            }
            .navigationTitle("Cancel “\(tripName)”?").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Keep trip") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
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
                        if detail.canPlan { Button { editing = nil; form = true } label: { Label("Add the first stop", systemImage: "plus").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButtonStyle()) }
                    }.cardStyle()
                } else if let day {
                    dayPicker(current: day.key)
                    mapCard(day)
                    VStack(spacing: 0) {
                        ForEach(Array(day.stops.enumerated()), id: \.element.id) { index, stop in
                            TimelineRow(stop: stop, previous: index > 0 ? day.stops[index - 1] : nil, isLast: index == day.stops.count - 1, trip: trip, canEdit: detail.canPlan, guesses: guesses) { editing = stop; form = true }
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
            if detail.canPlan {
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
                    TripMapView(stops: markers) { id in
                        if let stop = day.stops.first(where: { $0.id == id }), let url = ItineraryLogic.navigateURL(stop, guess: guesses[id]) { openURL(url) }
                    }
                }
            }
            .frame(height: 250).frame(maxWidth: .infinity).background(Theme.sage.opacity(0.5))
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Route for \(ItineraryLogic.dayLabel(day.key))").font(.subheadline.bold())
                    Text(located > markers.count ? "\(markers.count) on the map · \(located - markers.count) still locating" : "Tap a pin or a place to navigate there").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if let url = ItineraryLogic.dayDirectionsURL(day.stops, guesses: guesses) {
                    Button { openURL(url) } label: { Label("Day route", systemImage: "point.topleft.down.to.point.bottomright.curvepath").font(.subheadline.bold()) }
                        .buttonStyle(.bordered).tint(Theme.forest)
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
            // Tapping the place starts navigation from where you are now.
            if let location = stop.location, !location.isEmpty, let go = ItineraryLogic.navigateURL(stop, guess: guesses[stop.id]) {
                Button { openURL(go) } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "mappin").foregroundStyle(Theme.moss)
                        Text(location).foregroundStyle(Theme.forest).lineLimit(expanded ? 3 : 1).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
                        Label("Go", systemImage: "location.north.line.fill").font(.caption.bold()).foregroundStyle(.white)
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Theme.forest, in: Capsule())
                    }
                    .font(.caption).padding(.leading, 8).padding(.trailing, 4).padding(.vertical, 4)
                    .background(Theme.sage.opacity(0.45), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(PressableStyle())
                .accessibilityLabel("Navigate to \(location)")
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
                        Button { openURL(url) } label: { Label("View on map", systemImage: "map") }.buttonStyle(.bordered)
                    }
                    if let previous, !ItineraryLogic.samePlace(previous, stop), let url = ItineraryLogic.directionsURL(from: previous, to: stop, guesses: guesses) {
                        Button { openURL(url) } label: { Label("From previous stop", systemImage: "arrow.triangle.turn.up.right.diamond") }.buttonStyle(.bordered)
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
            if !detail.trip.isCancelled { Section { Button("Record an expense", systemImage: "plus") { adding = true } } }
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
    @State private var confirmDelete = false
    @Environment(\.dismiss) private var dismiss
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
                    // The organizer manages every expense; whoever recorded one may cancel or delete it.
                    let canManage = detail.is_organizer || (expense.created_by_member_id != nil && expense.created_by_member_id == detail.current_member_id)
                    let canDelete = canManage && (expense.payments ?? []).isEmpty && !(detail.trip.settlements ?? []).contains { $0.expense_id == expense.id }
                    if canManage && expense.status != "closed" && expense.status != "cancelled" {
                        Section("Expense actions") {
                            if detail.is_organizer && expense.status == "open" { Button("Mark merchant bill completed") { confirmation = "completed" } }
                            if detail.is_organizer && expense.status == "completed" { Button("Close expense") { confirmation = "closed" } }
                            TextField("Reason for cancelling", text: $cancellationReason)
                            if !(expense.payments ?? []).isEmpty { Toggle("Void merchant payments when cancelling", isOn: $voidPayments) }
                            Button("Cancel expense", role: .destructive) { confirmation = "cancelled" }.disabled(cancellationReason.isEmpty)
                        }
                    }
                    if canDelete {
                        Section {
                            Button("Delete expense", systemImage: "trash", role: .destructive) { confirmDelete = true }
                        } footer: { Text("For duplicates or mistakes. Removes it for everyone, including shares and receipt photos.") }
                    }
                }
                .navigationTitle(expense.title).navigationBarTitleDisplayMode(.inline)
                .confirmationDialog("Delete this expense?", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("Delete expense", role: .destructive) {
                        Task {
                            let deleted = await store.mutate { try await store.repository.deleteExpense(tripID: tripID, expenseID: expenseID) }
                            if deleted { dismiss() }
                        }
                    }
                } message: { Text("It is removed for everyone, including its shares and receipt photos.") }
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
