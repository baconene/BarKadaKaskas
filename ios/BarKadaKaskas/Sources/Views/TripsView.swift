import SwiftUI

struct TripsView: View {
    @EnvironmentObject var store: AppStore
    @State private var adding = false
    @State private var joining = false
    @State private var invitation = ""
    private var sorted: [Trip] { store.trips.sorted { $0.starts_on < $1.starts_on } }
    private func phase(_ trip: Trip) -> ItineraryLogic.Phase { ItineraryLogic.phase(start: trip.starts_on, end: trip.ends_on, timezone: trip.timezone) }
    private var active: [Trip] { sorted.filter { !$0.isCancelled && phase($0) != .past } }
    private var past: [Trip] { sorted.filter { $0.isCancelled || phase($0) == .past }.reversed() }
    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("YOUR NEXT CHAPTER").font(.caption.weight(.bold)).tracking(1.2).foregroundStyle(Theme.moss)
                        Text("Where to, \(store.user?.name.split(separator: " ").first.map(String.init) ?? "traveler")?").font(.largeTitle.bold())
                        Text("Plans, people and every shared peso, all in one place.").foregroundStyle(.secondary)
                    }.padding(.bottom, 4)
                    if store.trips.isEmpty && !store.busy {
                        VStack(spacing: 14) {
                            EmptyMessage(title: "Your adventures start here", subtitle: "Create a trip, invite your barkada, and leave the expense math to us.", icon: "suitcase.rolling.fill")
                            Button { adding = true } label: { Label("Plan your first trip", systemImage: "plus").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButtonStyle())
                        }.cardStyle()
                    }
                    if let next = active.first {
                        NavigationLink { TripDetailView(tripID: next.id) } label: { HeroTripCard(trip: next, phase: phase(next)) }.buttonStyle(PressableStyle())
                    }
                    if active.count > 1 {
                        SectionHeader(title: "Coming up", count: active.count - 1)
                        ForEach(Array(active.dropFirst().enumerated()), id: \.element.id) { index, trip in
                            NavigationLink { TripDetailView(tripID: trip.id) } label: { TripCard(trip: trip, index: index + 1, phase: phase(trip)) }.buttonStyle(PressableStyle())
                        }
                    }
                    if !past.isEmpty {
                        SectionHeader(title: past.contains(where: \.isCancelled) ? "Past & cancelled" : "Past trips", count: past.count)
                        ForEach(Array(past.enumerated()), id: \.element.id) { index, trip in
                            NavigationLink { TripDetailView(tripID: trip.id) } label: { TripCard(trip: trip, index: index, phase: .past) }.buttonStyle(PressableStyle())
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 100)
            }
            .background(Theme.canvas)
            .navigationTitle("My trips").navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                HStack {
                    Spacer()
                    Button { adding = true } label: { Label("Plan a trip", systemImage: "plus").font(.headline).padding(.horizontal, 22).padding(.vertical, 16) }
                        .foregroundStyle(.white).background(Theme.forest, in: Capsule()).shadow(color: Theme.forest.opacity(0.35), radius: 10, y: 5)
                        .buttonStyle(PressableStyle())
                        .accessibilityHint("Creates a new trip")
                }.padding(.horizontal, 20).padding(.bottom, 8)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Join with an invitation", systemImage: "link") { joining = true }
                        Button("Refresh", systemImage: "arrow.clockwise") { Task { await store.loadTrips() } }
                        Divider()
                        Button("Log out", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                            Task { await store.perform { try await store.repository.logout(); store.user = nil; store.detail = nil; store.trips = [] } }
                        }
                    } label: { Image(systemName: "person.crop.circle").font(.title3) }
                }
            }
            .task { await store.loadTrips() }
            .refreshable { await store.loadTrips() }
            .sensoryFeedback(.success, trigger: store.trips.count)
            .sheet(isPresented: $adding) { TripForm() }
            .alert("Join a trip", isPresented: $joining) {
                TextField("Invitation link or token", text: $invitation)
                Button("Join") {
                    Task {
                        let token = invitation.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "/").last.map(String.init) ?? ""
                        await store.perform { _ = try await store.repository.joinInvitation(token: token); store.trips = try await store.repository.trips() }
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Sign in to the same server that issued your invitation.") }
        }
    }
}

private struct SectionHeader: View {
    let title: String; let count: Int
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) { Text(title).font(.title3.bold()); Text("\(count)").foregroundStyle(.secondary) }.padding(.top, 10)
    }
}

/// The next (or ongoing) trip, big, with a countdown.
private struct HeroTripCard: View {
    let trip: Trip; let phase: ItineraryLogic.Phase
    private var isOngoing: Bool { if case .ongoing = phase { return true }; return phase == .upcoming(days: 0) }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label(ItineraryLogic.phaseLabel(phase), systemImage: "clock").font(.caption.bold()).padding(.horizontal, 10).padding(.vertical, 6).background(.white.opacity(0.16), in: Capsule())
                Spacer()
                Text(isOngoing ? "Happening now" : "Next trip")
                    .font(.caption.bold()).foregroundStyle(Theme.forest).padding(.horizontal, 10).padding(.vertical, 6).background(Theme.sage, in: Capsule())
            }
            Text(trip.destination.uppercased()).font(.caption.weight(.bold)).tracking(1.2).foregroundStyle(Theme.sage).padding(.top, 6)
            Text(trip.name).font(.title2.bold()).multilineTextAlignment(.leading)
            HStack(spacing: 16) {
                Label(ItineraryLogic.dateRange(trip.starts_on, trip.ends_on), systemImage: "calendar")
                if let count = trip.members_count { Label("\(count)", systemImage: "person.2.fill") }
            }.font(.subheadline).foregroundStyle(.white.opacity(0.85))
            HStack(spacing: 4) { Text("Open trip").fontWeight(.semibold); Image(systemName: "chevron.right") }.padding(.top, 4)
        }
        .foregroundStyle(.white)
        .padding(22).frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .bottomTrailing) {
            ZStack(alignment: .bottomTrailing) {
                LinearGradient(colors: [Theme.forest, Color(red: 0.18, green: 0.42, blue: 0.32)], startPoint: .topLeading, endPoint: .bottomTrailing)
                if let url = trip.cover_url.flatMap(URL.init(string:)) {
                    AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
                    // Darkens the photo so the white text stays readable.
                    LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                } else {
                    Image(systemName: "mountain.2.fill").font(.system(size: 150)).foregroundStyle(.white.opacity(0.07)).offset(x: 30, y: 30)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

private struct TripCard: View {
    let trip: Trip; let index: Int; let phase: ItineraryLogic.Phase
    private static let banners: [(Color, Color)] = [
        (Color(red: 0.86, green: 0.91, blue: 0.80), Color(red: 0.31, green: 0.44, blue: 0.27)),
        (Color(red: 0.93, green: 0.89, blue: 0.82), Color(red: 0.58, green: 0.45, blue: 0.27)),
        (Color(red: 0.86, green: 0.91, blue: 0.92), Color(red: 0.28, green: 0.47, blue: 0.49)),
    ]
    var body: some View {
        let banner = Self.banners[index % Self.banners.count]
        HStack(spacing: 14) {
            CoverThumbnail(url: trip.cover_url, tint: banner.1, background: banner.0)
            VStack(alignment: .leading, spacing: 4) {
                Text(trip.destination.uppercased()).font(.caption2.weight(.bold)).tracking(1).foregroundStyle(Theme.moss)
                Text(trip.name).font(.headline).foregroundStyle(.primary).lineLimit(1)
                Text("\(ItineraryLogic.dateRange(trip.starts_on, trip.ends_on)) · \(trip.members_count ?? 0) travelers").font(.caption).foregroundStyle(.secondary)
                if trip.isCancelled {
                    Label("Cancelled", systemImage: "xmark.octagon.fill").font(.caption2.bold()).foregroundStyle(Color(red: 0.63, green: 0.28, blue: 0.21))
                        .padding(.horizontal, 8).padding(.vertical, 3).background(Color(red: 0.99, green: 0.91, blue: 0.89), in: Capsule())
                } else {
                    Text(ItineraryLogic.phaseLabel(phase)).font(.caption2.bold()).foregroundStyle(phase == .past ? Color.secondary : Theme.forest)
                        .padding(.horizontal, 8).padding(.vertical, 3).background((phase == .past ? Color.gray : Theme.moss).opacity(0.14), in: Capsule())
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }
}

struct TripForm: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""; @State private var destination = ""; @State private var description = ""
    @State private var start = Date(); @State private var end = Date()
    @State private var currency = "PHP"
    @State private var timezone = TimeZone.current.identifier
    var body: some View {
        NavigationStack {
            Form {
                Section("The adventure") {
                    TextField("Trip name", text: $name)
                    TextField("Destination", text: $destination)
                    TextField("Description", text: $description, axis: .vertical)
                }
                Section("Dates & currency") {
                    DatePicker("Starts", selection: $start, displayedComponents: .date)
                    DatePicker("Ends", selection: $end, in: start..., displayedComponents: .date)
                    Picker("Currency", selection: $currency) { ForEach(["PHP", "USD", "EUR", "GBP", "SGD", "AUD", "CAD"], id: \.self) { Text($0) } }
                    TextField("Time zone", text: $timezone).textInputAutocapitalization(.never)
                }
            }
            .navigationTitle("New trip").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Create") {
                    Task {
                        // Parentheses are required for a closure inside an `if` condition.
                        if await store.perform({
                            guard TimeZone(identifier: timezone) != nil else { throw MobileError.validation("Enter a valid time zone, such as Asia/Manila.") }
                            _ = try await store.repository.createTrip(TripInput(name: name, destination: destination, description: description, starts_on: TravelFormat.date(start), ends_on: TravelFormat.date(max(start, end)), timezone: timezone, currency_code: currency))
                            store.trips = try await store.repository.trips()
                        }) { dismiss() }
                    }
                }.disabled(name.isEmpty || destination.isEmpty || store.busy) }
            }
        }
    }
}

/// Square trip thumbnail: the cover photo when there is one, otherwise a tinted pin.
struct CoverThumbnail: View {
    let url: String?; let tint: Color; let background: Color
    var size: CGFloat = 60
    var body: some View {
        ZStack {
            background
            if let link = url.flatMap(URL.init(string:)) {
                AsyncImage(url: link) { image in image.resizable().scaledToFill() } placeholder: { ProgressView() }
            } else {
                Image(systemName: "mappin.and.ellipse").font(.title2).foregroundStyle(tint)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
