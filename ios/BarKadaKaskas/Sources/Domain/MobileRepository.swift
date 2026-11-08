import Foundation

/// All methods are invoked on the main actor. Implement async URLSession work without blocking it.
/// Only the networking implementation stores tokens; views never receive credentials or bearer tokens.
@MainActor protocol MobileRepository: AnyObject {
    var serverURL: String { get }
    func configure(serverURL: String) throws
    func restoreSession() async throws -> AppUser?
    func login(email: String, password: String) async throws -> AppUser
    func register(name: String, email: String, password: String) async throws -> AppUser
    func logout() async throws
    func trips() async throws -> [Trip]
    func createTrip(_ input: TripInput) async throws -> Trip
    func trip(id: Int) async throws -> TripDetail
    func saveStop(tripID: Int, stopID: Int?, input: StopInput) async throws
    /// Ticks an activity off for everyone on the trip, or undoes it. Any traveller on the trip may do this.
    func setStopDone(tripID: Int, stopID: Int, done: Bool) async throws
    func createExpense(tripID: Int, input: ExpenseInput) async throws
    /// Removes an expense entered by mistake; only while no payments are recorded (organizer or its creator).
    func deleteExpense(tripID: Int, expenseID: Int) async throws
    func updateProfile(_ input: ProfileInput) async throws
    func uploadAvatar(jpeg: Data) async throws
    func removeAvatar() async throws
    func posts(tripID: Int, before: Int?) async throws -> PostPage
    /// `jpegs` are downscaled photos (1 to 10).
    func createPost(tripID: Int, caption: String, jpegs: [Data]) async throws -> TripPost
    func deletePost(tripID: Int, postID: Int) async throws
    /// Toggles the viewer's heart; returns whether it is now liked and the new count.
    func toggleLike(tripID: Int, postID: Int) async throws -> (liked: Bool, count: Int)
    /// Reads a receipt photo (downscaled JPEG) into an expense draft.
    func scanReceipt(tripID: Int, jpeg: Data) async throws -> ReceiptDraft
    func changeExpenseStatus(tripID: Int, expenseID: Int, version: Int, status: String, reason: String?, voidPayments: Bool) async throws
    func markSharePaid(tripID: Int, expenseID: Int, memberID: Int, version: Int) async throws
    func uploadReceipt(tripID: Int, expenseID: Int, data: Data, fileName: String, mimeType: String) async throws
    func createSettlement(tripID: Int, input: SettlementInput) async throws
    func confirmSettlement(tripID: Int, settlementID: Int) async throws
    func createInvitation(tripID: Int) async throws -> URL
    func joinInvitation(token: String) async throws -> Int
    /// Organizer only. The server keeps the currency once expenses exist.
    func updateTrip(tripID: Int, input: TripInput) async throws
    /// Organizer only. `jpeg` is an already downscaled JPEG.
    func uploadCover(tripID: Int, jpeg: Data) async throws
    func removeCover(tripID: Int) async throws
    /// Organizer only. `hide` also removes the trip from everyone's trip list.
    func cancelTrip(tripID: Int, reason: String?, hide: Bool) async throws
    func reopenTrip(tripID: Int) async throws
    func setTripHidden(tripID: Int, hidden: Bool) async throws
}

enum MobileError: LocalizedError {
    case validation(String)
    var errorDescription: String? {
        switch self {
        case .validation(let message): return message
        }
    }
}
