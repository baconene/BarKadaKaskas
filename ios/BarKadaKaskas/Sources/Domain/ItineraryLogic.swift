import Foundation
import CoreLocation

/// Itinerary helpers shared by the trip screens. Mirrors resources/js/lib/itinerary.ts on the web.
enum ItineraryLogic {
    struct Day: Identifiable {
        let key: String          // yyyy-MM-dd in the trip's timezone
        let stops: [ItineraryStop]
        var id: String { key }
    }

    enum Phase: Equatable { case upcoming(days: Int), ongoing(day: Int, of: Int), past }

    static func ordered(_ stops: [ItineraryStop]) -> [ItineraryStop] {
        stops.sorted { lhs, rhs in
            let a = TravelFormat.parse(lhs.starts_at), b = TravelFormat.parse(rhs.starts_at)
            return a == b ? lhs.id < rhs.id : a < b
        }
    }

    static func dayKey(_ value: String, timezone: String) -> String {
        TravelFormat.date(TravelFormat.parse(value), timezone: timezone)
    }

    static func days(_ stops: [ItineraryStop], timezone: String) -> [Day] {
        var groups: [String: [ItineraryStop]] = [:]
        for stop in ordered(stops) { groups[dayKey(stop.starts_at, timezone: timezone), default: []].append(stop) }
        return groups.keys.sorted().map { Day(key: $0, stops: groups[$0] ?? []) }
    }

    private static func calendarDate(_ key: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = .gmt; formatter.dateFormat = "yyyy-MM-dd"
        return formatter.date(from: String(key.prefix(10)))
    }

    private static func format(_ key: String, _ pattern: String) -> String {
        guard let date = calendarDate(key) else { return key }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US"); formatter.timeZone = .gmt; formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    static func weekday(_ key: String) -> String { format(key, "EEE").uppercased() }
    static func monthDay(_ key: String) -> String { format(key, "MMM d") }
    static func dayLabel(_ key: String) -> String { format(key, "EEE, MMM d") }

    static func clock(_ value: String, timezone: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US"); formatter.timeZone = TimeZone(identifier: timezone) ?? .gmt; formatter.dateFormat = "h:mm a"
        return formatter.string(from: TravelFormat.parse(value))
    }

    /// "Nov 11 – 17, 2026", "Nov 28 – Dec 2, 2026" or "Dec 30, 2026 – Jan 2, 2027".
    static func dateRange(_ start: String, _ end: String) -> String {
        let from = String(start.prefix(10)), to = String(end.prefix(10))
        guard calendarDate(from) != nil, calendarDate(to) != nil else { return "\(from) – \(to)" }
        if from == to { return format(from, "MMM d, yyyy") }
        if from.prefix(4) != to.prefix(4) { return "\(format(from, "MMM d, yyyy")) – \(format(to, "MMM d, yyyy"))" }
        if from.prefix(7) == to.prefix(7) { return "\(format(from, "MMM d")) – \(format(to, "d, yyyy"))" }
        return "\(format(from, "MMM d")) – \(format(to, "MMM d, yyyy"))"
    }

    static func phase(start: String, end: String, timezone: String, now: Date = Date()) -> Phase {
        let today = TravelFormat.date(now, timezone: timezone)
        guard let first = calendarDate(start), let last = calendarDate(end), let current = calendarDate(today) else { return .upcoming(days: 0) }
        let day: TimeInterval = 86_400
        if current < first { return .upcoming(days: Int((first.timeIntervalSince(current) / day).rounded())) }
        if current > last { return .past }
        return .ongoing(day: Int((current.timeIntervalSince(first) / day).rounded()) + 1, of: Int((last.timeIntervalSince(first) / day).rounded()) + 1)
    }

    static func phaseLabel(_ phase: Phase) -> String {
        switch phase {
        case .upcoming(let days): return days == 0 ? "Starts today" : days == 1 ? "Tomorrow!" : "In \(days) days"
        case .ongoing(let day, let total): return "Day \(day) of \(total)"
        case .past: return "Completed"
        }
    }

    static func coordinate(_ stop: ItineraryStop) -> CLLocationCoordinate2D? {
        guard let lat = stop.latitude, let lng = stop.longitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }

    static func samePlace(_ a: ItineraryStop, _ b: ItineraryStop) -> Bool {
        if let one = coordinate(a), let two = coordinate(b) { return abs(one.latitude - two.latitude) < 0.00045 && abs(one.longitude - two.longitude) < 0.00045 }
        let first = (a.location ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        return !first.isEmpty && first == (b.location ?? "").trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// Prefers exact coordinates (stored or looked up) over the typed address.
    static func point(_ stop: ItineraryStop, guess: CLLocationCoordinate2D? = nil) -> String? {
        guard let location = stop.location?.trimmingCharacters(in: .whitespaces), !location.isEmpty else { return nil }
        if let pin = coordinate(stop) ?? guess { return "\(pin.latitude),\(pin.longitude)" }
        return location
    }

    static func travelMode(_ transport: String?) -> String {
        switch transport {
        case "walking": return "walking"
        case "bicycle": return "bicycling"
        case "transit", "ferry": return "transit"
        case "motorcycle": return "two-wheeler"
        default: return "driving"
        }
    }

    private static func mapsURL(_ path: String, _ items: [URLQueryItem]) -> URL? {
        var components = URLComponents(string: "https://www.google.com/maps/\(path)/")!
        components.queryItems = [URLQueryItem(name: "api", value: "1")] + items
        return components.url
    }

    static func searchURL(_ stop: ItineraryStop, guess: CLLocationCoordinate2D? = nil) -> URL? {
        mapsURL("search", [URLQueryItem(name: "query", value: point(stop, guess: guess) ?? stop.title)])
    }

    static func directionsURL(from: ItineraryStop, to: ItineraryStop, guesses: [Int: CLLocationCoordinate2D] = [:]) -> URL? {
        guard let origin = point(from, guess: guesses[from.id]), let destination = point(to, guess: guesses[to.id]) else { return nil }
        return mapsURL("dir", [URLQueryItem(name: "origin", value: origin), URLQueryItem(name: "destination", value: destination), URLQueryItem(name: "travelmode", value: travelMode(to.transport_mode))])
    }

    /// Google Maps directions through a day's stops in order (repeats removed, at most 9 waypoints).
    static func dayDirectionsURL(_ stops: [ItineraryStop], guesses: [Int: CLLocationCoordinate2D] = [:]) -> URL? {
        var points: [String] = []
        for stop in stops { if let point = point(stop, guess: guesses[stop.id]), points.last != point { points.append(point) } }
        guard points.count >= 2 else { return nil }
        var items = [URLQueryItem(name: "origin", value: points.first), URLQueryItem(name: "destination", value: points.last), URLQueryItem(name: "travelmode", value: "driving")]
        let waypoints = points.dropFirst().dropLast().prefix(9)
        if !waypoints.isEmpty { items.append(URLQueryItem(name: "waypoints", value: waypoints.joined(separator: "|"))) }
        return mapsURL("dir", items)
    }

    static func costRange(_ min: Int?, _ max: Int?, currency: String) -> String? {
        guard let min, let max else { return nil }
        return min == max ? TravelFormat.wholeMoney(min, currency: currency) : "\(TravelFormat.wholeMoney(min, currency: currency))–\(TravelFormat.wholeMoney(max, currency: currency).filter { $0.isNumber || $0 == "," || $0 == "." })"
    }

    static func totalCost(_ stops: [ItineraryStop]) -> (Int, Int)? {
        let priced = stops.filter { $0.cost_min_minor != nil && $0.cost_max_minor != nil }
        guard !priced.isEmpty else { return nil }
        return (priced.reduce(0) { $0 + ($1.cost_min_minor ?? 0) }, priced.reduce(0) { $0 + ($1.cost_max_minor ?? 0) })
    }

    /// SF Symbol for a best-guess activity type, from its title.
    static func symbol(for title: String) -> String {
        let rules: [(String, [String])] = [
            ("airplane", ["flight", "airport"]),
            ("ferry.fill", ["ferry", "port", "boat", "pier"]),
            ("fork.knife", ["breakfast", "lunch", "dinner", "brunch", "cafe", "café", "coffee", "restaurant", "food", "eat", "snack"]),
            ("bed.double.fill", ["check in", "check-in", "check out", "hotel", "resort", "night", "rest", "relax", "cove", "inn", "hostel"]),
            ("beach.umbrella.fill", ["beach", "snorkel", "swim", "dive", "falls", "spring", "sunset", "island"]),
            ("bag.fill", ["shop", "souvenir", "market", "mall"]),
            ("camera.fill", ["church", "cave", "tour", "museum", "viewpoint", "tree", "park", "sightseeing", "hike", "boulevard"]),
        ]
        let words = title.lowercased()
        return rules.first { rule in rule.1.contains { words.range(of: "\\b\($0)\\b", options: .regularExpression) != nil } }?.0 ?? "mappin.circle.fill"
    }

    static func transportSymbol(_ mode: String?) -> String {
        switch mode {
        case "walking": return "figure.walk"
        case "bicycle": return "bicycle"
        case "motorcycle": return "scooter"
        case "transit": return "bus.fill"
        case "ferry": return "ferry.fill"
        case "flight": return "airplane"
        default: return "car.fill"
        }
    }
}

/// Looks up typed addresses with Apple's geocoder (no API key), remembering answers for the session.
actor PlaceLookup {
    static let shared = PlaceLookup()
    private var cache: [String: CLLocationCoordinate2D] = [:]
    private var misses: Set<String> = []

    /// Appends the trip destination when the address does not mention it, to avoid matches in other countries.
    nonisolated static func query(_ location: String, destination: String) -> String {
        let place = location.trimmingCharacters(in: .whitespaces)
        let area = destination.split(separator: ",").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        return area.isEmpty || place.localizedCaseInsensitiveContains(area) ? place : "\(place), \(destination)"
    }

    func coordinate(for query: String) async -> CLLocationCoordinate2D? {
        let key = query.lowercased()
        if key.isEmpty || misses.contains(key) { return nil }
        if let hit = cache[key] { return hit }
        let found = try? await CLGeocoder().geocodeAddressString(query).first?.location?.coordinate
        if let found { cache[key] = found } else { misses.insert(key) }
        return found
    }
}
