import Foundation

/// A trip member's user, with the contact details they share with their trip-mates.
struct MemberUser: Codable, Hashable {
    let id: Int?; let name: String; let email: String?
    var phone: String? = nil; var other_contact: String? = nil; var avatar_url: String? = nil
    var emergency_contact_name: String? = nil; var emergency_contact_relationship: String? = nil; var emergency_contact_phone: String? = nil
}

/// Your own contact details. Empty fields are sent as null so they can be cleared.
struct ProfileInput: Encodable {
    let phone: String?; let other_contact: String?
    let emergency_contact_name: String?; let emergency_contact_relationship: String?; let emergency_contact_phone: String?
    enum CodingKeys: String, CodingKey { case phone, other_contact, emergency_contact_name, emergency_contact_relationship, emergency_contact_phone }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(phone, forKey: .phone); try c.encode(other_contact, forKey: .other_contact)
        try c.encode(emergency_contact_name, forKey: .emergency_contact_name); try c.encode(emergency_contact_relationship, forKey: .emergency_contact_relationship)
        try c.encode(emergency_contact_phone, forKey: .emergency_contact_phone)
    }
}

/// A post in the trip's photo feed.
struct TripPost: Decodable, Identifiable {
    struct Author: Decodable { let member_id: Int; let name: String; let avatar_url: String? }
    struct Photo: Decodable, Hashable { let url: String; let width: Int?; let height: Int? }
    let id: Int
    let caption: String?
    let created_at: String
    let author: Author
    let photos: [Photo]
    var likes_count: Int
    var liked: Bool
    let can_delete: Bool
}
struct PostPage: Decodable { let data: [TripPost]; let next_before: Int? }
struct AppUser: Codable, Identifiable { let id: Int; let name: String; let email: String }
struct TripMember: Codable, Identifiable { let id: Int; let status: String?; let user: MemberUser }
struct ExpenseCategory: Codable, Identifiable, Hashable { let id: Int; let name: String }
struct Trip: Codable, Identifiable {
    let id: Int
    let name: String
    let destination: String
    let description: String?
    let starts_on: String
    let ends_on: String
    let timezone: String
    let currency_code: String
    /// "planning" or "cancelled"; optional so older servers still decode.
    let status: String?
    let cancellation_reason: String?
    /// Set when the organizer hid this cancelled trip from trip lists.
    let hidden_at: String?
    var isCancelled: Bool { status == "cancelled" }
    /// Organizer's cover photo; a plain public image URL.
    let cover_url: String?
    let members_count: Int?
    let members: [TripMember]?
    let itinerary_items: [ItineraryStop]?
    let expenses: [Expense]?
    let settlements: [Settlement]?
}
struct ItineraryStop: Codable, Identifiable {
    let id: Int
    let title: String
    let description: String?
    let location: String?
    let starts_at: String
    let ends_at: String
    let transport_mode: String?
    /// Set when the place was picked from search (web) or looked up on the device; nil for unresolved typed addresses.
    let latitude: Double?
    let longitude: Double?
    let cost_min_minor: Int?
    let cost_max_minor: Int?
    let cost_note: String?
}
struct MerchantPayment: Codable { let trip_member_id: Int; let amount_minor: Int }
struct Allocation: Codable { let trip_member_id: Int; let amount_minor: Int }
struct ExpenseReceipt: Codable, Identifiable { let id: Int; let processing_status: String }
struct Expense: Codable, Identifiable {
    let id: Int
    /// Trip member who recorded the expense; they may cancel or delete it.
    let created_by_member_id: Int?
    let title: String
    let merchant: String?
    let status: String
    let version: Int
    let total_minor: Int
    let incurred_at: String
    let split_method: String
    let category: ExpenseCategory?
    let payments: [MerchantPayment]?
    let allocations: [Allocation]?
    let receipts: [ExpenseReceipt]?
}
struct Balance: Codable, Identifiable {
    var id: Int { member_id }
    let member_id: Int
    let name: String
    let net_minor: Int
}
struct ExpenseShare: Codable, Identifiable {
    var id: Int { member_id }
    let member_id: Int
    let share_minor: Int
    let covered_minor: Int
    let remaining_minor: Int
}
struct Settlement: Codable, Identifiable {
    let id: Int
    let expense_id: Int?
    let from_member_id: Int
    let to_member_id: Int
    let amount_minor: Int
    let payment_method: String
    let status: String
    let paid_at: String?
    let confirmed_at: String?
}
struct TripDetail: Codable {
    let trip: Trip
    let balances: [Balance]
    let expense_shares: [String: [ExpenseShare]]
    let current_member_id: Int
    let is_organizer: Bool
    let categories: [ExpenseCategory]
    /// Organizer and the trip is not cancelled: may change the plan, add stops and invite.
    var canPlan: Bool { is_organizer && !trip.isCancelled }
        func name(_ id: Int) -> String { trip.members?.first { $0.id == id }?.user.name ?? "Traveler" }
}

struct TripInput: Encodable {
    let name: String; let destination: String; let description: String
    let starts_on: String; let ends_on: String; let timezone: String; let currency_code: String
}
struct StopInput: Encodable {
    let title: String; let location: String; let description: String
    let starts_at: String; let ends_at: String; let transport_mode: String?
    /// Keeps (or sets) the map pin. Omitted when nil, which makes the server clear the pin.
    var latitude: Double? = nil
    var longitude: Double? = nil
}
struct ExpenseInput: Encodable {
    struct Payment: Encodable { let trip_member_id: Int; let amount_minor: Int }
    struct Share: Encodable { let trip_member_id: Int; let weight: Int }
    let title: String; let merchant: String; let category_id: Int; let incurred_at: String
    struct Item: Encodable { let description: String; let amount_minor: Int; let member_ids: [Int] }
    let total_minor: Int; let split_method: String; let payments: [Payment]; let allocations: [Share]
    /// Receipt lines for itemized splits (must add up to the total); empty for equal splits.
    var items: [Item] = []
    /// A receipt scan to attach to the new expense.
    var receipt_scan_id: Int? = nil
}

/// What the server read from a receipt photo. Everything is a suggestion the traveller can edit.
struct ReceiptDraft: Decodable {
    struct Line: Decodable { let description: String; let amount_minor: Int }
    let scan_id: Int
    let title: String
    let merchant: String?
    let incurred_at: String?
    let category_id: Int?
    let total_minor: Int?
    let lines: [Line]
    let warnings: [String]
}
struct CancelTripInput: Encodable { let reason: String?; let hide: Bool }
struct TripVisibilityInput: Encodable { let hidden: Bool }
struct SettlementInput: Encodable { let to_member_id: Int; let amount_minor: Int; let payment_method: String }

enum TravelFormat {
    static func money(_ minor: Int, currency: String) -> String {
        (Decimal(minor) / 100).formatted(.currency(code: currency))
    }
    /// Drops ".00" for whole amounts, e.g. "₱1,430".
    static func wholeMoney(_ minor: Int, currency: String) -> String {
        let style = Decimal.FormatStyle.Currency(code: currency).precision(.fractionLength(minor % 100 == 0 ? 0 : 2))
        return (Decimal(minor) / 100).formatted(style)
    }
    static func minor(_ input: String) -> Int? {
        guard let amount = Decimal(string: input, locale: Locale(identifier: "en_US_POSIX")), amount > 0 else { return nil }
        let value = amount * 100
        var rounded = Decimal(); var source = value
        NSDecimalRound(&rounded, &source, 0, .plain)
        guard rounded == value, rounded <= Decimal(Int.max) else { return nil }
        return NSDecimalNumber(decimal: rounded).intValue
    }
    static func date(_ date: Date, timezone: String = "UTC", includeTime: Bool = false) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: timezone) ?? .gmt
        formatter.dateFormat = includeTime ? "yyyy-MM-dd'T'HH:mm" : "yyyy-MM-dd"
        return formatter.string(from: date)
    }
    static func parse(_ text: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: text) { return date }
        let simple = DateFormatter(); simple.dateFormat = "yyyy-MM-dd"; simple.timeZone = .gmt
        return simple.date(from: String(text.prefix(10))) ?? Date()
    }
    static func display(_ value: String, timezone: String) -> String {
        let formatter = DateFormatter(); formatter.dateStyle = .medium; formatter.timeStyle = .short
        formatter.timeZone = TimeZone(identifier: timezone) ?? .gmt
        return formatter.string(from: parse(value))
    }
    static func maps(_ location: String) -> URL? {
        var components = URLComponents(string: "https://www.google.com/maps/search/")!
        components.queryItems = [URLQueryItem(name: "api", value: "1"), URLQueryItem(name: "query", value: location)]
        return components.url
    }
    static let transports: [(String, String)] = [("", "Starting point / decide later"), ("car", "Car"), ("van", "Van"), ("taxi", "Taxi / ride-hailing"), ("motorcycle", "Motorcycle"), ("tricycle", "Tricycle"), ("walking", "Walking"), ("bicycle", "Bicycle"), ("transit", "Public transit"), ("ferry", "Ferry / boat"), ("flight", "Flight"), ("other", "Other")]
    static func transport(_ mode: String?) -> String { transports.first { $0.0 == mode }?.1 ?? "Transport to be decided" }
}
