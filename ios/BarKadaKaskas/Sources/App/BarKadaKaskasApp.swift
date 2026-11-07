import SwiftUI

@main struct BarKadaKaskasApp: App {
    // Laravel API over URLSession, with the token kept in the Keychain.
    @StateObject private var store = AppStore(repository: LaravelRepository())
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).tint(Theme.forest)
                .task { await store.restore() }
        }
    }
}

@MainActor final class AppStore: ObservableObject {
    @Published var user: AppUser?
    @Published var trips: [Trip] = []
    @Published var detail: TripDetail?
    @Published var busy = false
    @Published var ready = false
    @Published var error: String?
    let repository: any MobileRepository
    init(repository: any MobileRepository) { self.repository = repository }
    func restore() async {
        do { user = try await repository.restoreSession() }
        catch { self.error = error.localizedDescription }
        ready = true
    }
    @discardableResult func perform(_ action: () async throws -> Void) async -> Bool {
        guard !busy else { return false }
        busy = true; error = nil
        defer { busy = false }
        do { try await action(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    func loadTrips() async { await perform { trips = try await repository.trips() } }
    func loadTrip(_ id: Int) async { await perform { detail = try await repository.trip(id: id) } }
    func mutate(_ action: () async throws -> Void) async -> Bool {
        await perform {
            try await action()
            if let id = detail?.trip.id { detail = try await repository.trip(id: id) }
        }
    }
}

enum Theme {
    static let forest = Color(red: 0.14, green: 0.30, blue: 0.24)
    static let canvas = Color(red: 0.96, green: 0.97, blue: 0.94)
}

struct RootView: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        Group {
            if !store.ready { ProgressView("Getting ready…") }
            else if store.user == nil { AuthView() }
            else { TripsView() }
        }
        .alert("Please check", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
            Button("OK", role: .cancel) { store.error = nil }
        } message: { Text(store.error ?? "") }
        .overlay(alignment: .top) { if store.busy { ProgressView().padding(10).background(.regularMaterial, in: Capsule()).accessibilityLabel("Working") } }
    }
}

struct EmptyMessage: View {
    let title: String; let subtitle: String; let icon: String
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 36)).foregroundStyle(Theme.forest)
            Text(title).font(.title3.bold())
            Text(subtitle).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity).padding(30)
    }
}
