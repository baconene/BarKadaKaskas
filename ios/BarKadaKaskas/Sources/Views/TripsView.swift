import SwiftUI

struct TripsView: View {
    @EnvironmentObject var store: AppStore
    @State private var adding = false
    @State private var joining = false
    @State private var invitation = ""
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Hi, \(store.user?.name ?? "traveler").").font(.title2.bold())
                    Text("Your next shared adventure starts here.").foregroundStyle(.secondary)
                }.listRowBackground(Color.clear)
                if store.trips.isEmpty && !store.busy {
                    EmptyMessage(title: "Make room for an adventure", subtitle: "Create a trip or join your friends with an invitation.", icon: "suitcase.rolling")
                }
                ForEach(store.trips) { trip in
                    NavigationLink { TripDetailView(tripID: trip.id) } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(trip.destination.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(Theme.forest)
                            Text(trip.name).font(.title3.bold())
                            Label("\(trip.starts_on.prefix(10)) – \(trip.ends_on.prefix(10))", systemImage: "calendar").font(.caption).foregroundStyle(.secondary)
                            if let count = trip.members_count { Label("\(count) travelers", systemImage: "person.2").font(.caption) }
                        }.padding(.vertical, 8)
                    }
                }
            }
            .scrollContentBackground(.hidden).background(Theme.canvas)
            .navigationTitle("My trips")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("New trip", systemImage: "plus") { adding = true } }
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("Join with an invitation", systemImage: "link") { joining = true }
                        Button("Log out", systemImage: "rectangle.portrait.and.arrow.right") {
                            Task { await store.perform { try await store.repository.logout(); store.user = nil; store.detail = nil; store.trips = [] } }
                        }
                    } label: { Image(systemName: "person.crop.circle") }
                }
            }
            .task { await store.loadTrips() }
            .refreshable { await store.loadTrips() }
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
