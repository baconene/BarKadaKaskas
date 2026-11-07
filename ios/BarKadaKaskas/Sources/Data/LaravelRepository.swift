import Foundation
import UIKit

/// A failure the user can act on; `errorDescription` is shown as-is in the app.
struct APIError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

/// Talks to the Laravel API (/api/v1) with a bearer token kept in the Keychain. See docs/mobile-api.md.
@MainActor final class LaravelRepository: MobileRepository {
    private static let serverKey = "barkada.serverURL"
    /// The BarkadaKasKas server every install talks to.
    static let defaultServerURL = "https://barkadkaskas.on-forge.com"
    private let session: URLSession
    private let defaults: UserDefaults
    private(set) var serverURL: String

    init(session: URLSession = .shared, defaults: UserDefaults = .standard) {
        self.session = session
        self.defaults = defaults
        // Fixed server; people never type it. Older versions saved one in defaults, which is ignored now.
        self.serverURL = Self.defaultServerURL
        defaults.removeObject(forKey: Self.serverKey)
    }

    // MARK: Session

    func configure(serverURL: String) throws {
        var value = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        if value.hasSuffix("/api/v1") { value.removeLast("/api/v1".count) }
        if !value.hasPrefix("http://") && !value.hasPrefix("https://") { value = "https://" + value }
        guard let url = URL(string: value), url.host != nil else { throw MobileError.validation("That server address doesn't look right.") }
        // A token only works on the server that issued it.
        if value != self.serverURL { TokenKeychain.save(nil) }
        self.serverURL = value
        defaults.set(value, forKey: Self.serverKey)
    }

    func restoreSession() async throws -> AppUser? {
        guard !serverURL.isEmpty, TokenKeychain.read() != nil else { return nil }
        do { return try await send(UserBox.self, "GET", "/auth/me").user }
        catch let error as APIError where error.status == 401 { return nil }
    }

    func login(email: String, password: String) async throws -> AppUser {
        let body = ["email": email.trimmingCharacters(in: .whitespaces), "password": password, "device_name": deviceName]
        let result = try await send(AuthResult.self, "POST", "/auth/login", json: body, auth: false)
        TokenKeychain.save(result.token)
        return result.user
    }

    func register(name: String, email: String, password: String) async throws -> AppUser {
        let body = ["name": name.trimmingCharacters(in: .whitespaces), "email": email.trimmingCharacters(in: .whitespaces), "password": password, "password_confirmation": password, "device_name": deviceName]
        let result = try await send(AuthResult.self, "POST", "/auth/register", json: body, auth: false)
        TokenKeychain.save(result.token)
        return result.user
    }

    func logout() async throws {
        // Revoke this device's token on the server, but always sign out locally.
        defer { TokenKeychain.save(nil) }
        if TokenKeychain.read() != nil { _ = try? await request("POST", "/auth/logout") }
    }

    // MARK: Trips

    func trips() async throws -> [Trip] { try await send(DataBox<[Trip]>.self, "GET", "/trips").data }
    func createTrip(_ input: TripInput) async throws -> Trip { try await send(DataBox<Trip>.self, "POST", "/trips", json: input).data }
    func trip(id: Int) async throws -> TripDetail { try await send(DataBox<TripDetail>.self, "GET", "/trips/\(id)").data }

    func saveStop(tripID: Int, stopID: Int?, input: StopInput) async throws {
        if let stopID { try await call("PATCH", "/trips/\(tripID)/itinerary/\(stopID)", json: input) }
        else { try await call("POST", "/trips/\(tripID)/itinerary", json: input) }
    }

    // MARK: Money

    func createExpense(tripID: Int, input: ExpenseInput) async throws { try await call("POST", "/trips/\(tripID)/expenses", json: input) }

    func changeExpenseStatus(tripID: Int, expenseID: Int, version: Int, status: String, reason: String?, voidPayments: Bool) async throws {
        try await call("PATCH", "/trips/\(tripID)/expenses/\(expenseID)/status", json: StatusChange(status: status, version: version, reason: reason, void_payments: voidPayments))
    }

    /// The server applies an existing reimbursement or asks the user to choose, so money is never counted twice.
    func markSharePaid(tripID: Int, expenseID: Int, memberID: Int, version: Int) async throws {
        try await call("POST", "/trips/\(tripID)/expenses/\(expenseID)/shares/paid", json: SharePaid(member_id: memberID, version: version))
    }

    func uploadReceipt(tripID: Int, expenseID: Int, data: Data, fileName: String, mimeType: String) async throws {
        try await upload("/trips/\(tripID)/expenses/\(expenseID)/receipts", field: "receipt", data: data, fileName: fileName, mimeType: mimeType)
    }

    func scanReceipt(tripID: Int, jpeg: Data) async throws -> ReceiptDraft {
        let body = try await upload("/trips/\(tripID)/receipt-scans", field: "receipt", data: jpeg, fileName: "receipt.jpg", mimeType: "image/jpeg")
        return try JSONDecoder().decode(DataBox<ReceiptDraft>.self, from: body).data
    }

    /// Sends one file as multipart/form-data and returns the response body.
    @discardableResult
    private func upload(_ path: String, field: String, data: Data, fileName: String, mimeType: String) async throws -> Data {
        let boundary = "BarKadaKaskas-\(UUID().uuidString)"
        let safeName = fileName.replacingOccurrences(of: "\"", with: "").replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(field)\"; filename=\"\(safeName)\"\r\nContent-Type: \(mimeType)\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return try await request("POST", path, body: body, contentType: "multipart/form-data; boundary=\(boundary)")
    }

    func createSettlement(tripID: Int, input: SettlementInput) async throws { try await call("POST", "/trips/\(tripID)/settlements", json: input) }
    func confirmSettlement(tripID: Int, settlementID: Int) async throws { try await call("POST", "/trips/\(tripID)/settlements/\(settlementID)/confirm") }

    // MARK: Trip lifecycle

    func updateTrip(tripID: Int, input: TripInput) async throws { try await call("PATCH", "/trips/\(tripID)", json: input) }
    func uploadCover(tripID: Int, jpeg: Data) async throws { try await upload("/trips/\(tripID)/cover", field: "cover", data: jpeg, fileName: "cover.jpg", mimeType: "image/jpeg") }
    func removeCover(tripID: Int) async throws { try await call("DELETE", "/trips/\(tripID)/cover") }

    func cancelTrip(tripID: Int, reason: String?, hide: Bool) async throws {
        let trimmed = reason?.trimmingCharacters(in: .whitespacesAndNewlines)
        try await call("POST", "/trips/\(tripID)/cancel", json: CancelTripInput(reason: trimmed?.isEmpty == false ? trimmed : nil, hide: hide))
    }
    func reopenTrip(tripID: Int) async throws { try await call("POST", "/trips/\(tripID)/reopen") }
    func setTripHidden(tripID: Int, hidden: Bool) async throws { try await call("PATCH", "/trips/\(tripID)/visibility", json: TripVisibilityInput(hidden: hidden)) }

    // MARK: Invitations

    func createInvitation(tripID: Int) async throws -> URL {
        let link = try await send(DataBox<InvitationLink>.self, "POST", "/trips/\(tripID)/invitations").data
        guard let url = URL(string: link.url) else { throw APIError(status: 0, message: "The server sent an invalid invitation link.") }
        return url
    }

    func joinInvitation(token: String) async throws -> Int {
        let cleaned = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, let encoded = cleaned.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) else { throw MobileError.validation("Paste the invitation link or token.") }
        return try await send(DataBox<JoinResult>.self, "POST", "/invitations/\(encoded)/join").data.trip_id
    }

    // MARK: HTTP

    private var deviceName: String { String("\(UIDevice.current.model) (\(UIDevice.current.systemName) \(UIDevice.current.systemVersion))".prefix(120)) }

    private func send<T: Decodable>(_ type: T.Type, _ method: String, _ path: String, json: (any Encodable)? = nil, auth: Bool = true) async throws -> T {
        let data = try await request(method, path, body: try json.map { try JSONEncoder().encode($0) }, auth: auth)
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw APIError(status: 0, message: "The server sent an unexpected response.") }
    }

    private func call(_ method: String, _ path: String, json: (any Encodable)? = nil) async throws {
        _ = try await request(method, path, body: try json.map { try JSONEncoder().encode($0) })
    }

    private func request(_ method: String, _ path: String, body: Data? = nil, contentType: String = "application/json", auth: Bool = true) async throws -> Data {
        guard !serverURL.isEmpty, let url = URL(string: serverURL + "/api/v1" + path) else { throw MobileError.validation("Enter your server address first.") }
        // Uploads (receipt scans run Gemini on the server) can take longer than plain API calls.
        var request = URLRequest(url: url, timeoutInterval: contentType.hasPrefix("multipart") ? 90 : 30)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        } else if method != "GET" {
            request.httpBody = Data("{}".utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if auth {
            guard let token = TokenKeychain.read() else { throw APIError(status: 401, message: "Please log in again.") }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let result: (Data, URLResponse)
        do { result = try await session.data(for: request) }
        catch let error as URLError where error.code == .timedOut { throw APIError(status: 0, message: "The server took too long to respond. Please try again.") }
        catch { throw APIError(status: 0, message: "Can't reach the server. Check the address and your internet connection.") }
        let (data, response) = result
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            if status == 401 && auth { TokenKeychain.save(nil) }
            throw APIError(status: status, message: Self.friendly(status, Self.serverMessage(data)))
        }
        return data
    }

    /// First readable message from a Laravel error body ({ message, errors: { field: [..] } }).
    private static func serverMessage(_ data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let errors = json["errors"] as? [String: Any], let first = errors.values.first as? [String], let message = first.first, !message.isEmpty { return message }
        return (json["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    private static func friendly(_ status: Int, _ message: String?) -> String {
        switch status {
        case 401: return "Your session has ended. Please log in again."
        case 403: return message.flatMap { $0 == "This action is unauthorized." ? nil : $0 } ?? "You don't have permission to do that."
        case 404: return "That item no longer exists. Refresh and try again."
        case 409, 422: return message ?? "Please check the details and try again."
        case 410: return message ?? "This invitation has expired."
        case 429: return "Too many attempts. Please wait a minute and try again."
        case 500...599: return "The server had a problem. Please try again later."
        default: return message ?? "Something went wrong (HTTP \(status))."
        }
    }
}

// MARK: Wire types

private struct DataBox<T: Decodable>: Decodable { let data: T }
private struct UserBox: Decodable { let user: AppUser }
private struct AuthResult: Decodable { let token: String; let user: AppUser }
private struct InvitationLink: Decodable { let url: String }
private struct JoinResult: Decodable { let trip_id: Int }
private struct StatusChange: Encodable { let status: String; let version: Int; let reason: String?; let void_payments: Bool }
private struct SharePaid: Encodable { let member_id: Int; let version: Int }
