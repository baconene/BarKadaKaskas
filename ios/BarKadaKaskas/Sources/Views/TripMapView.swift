import SwiftUI
import MapKit

/// One numbered pin on the trip map. `approximate` pins come from looking up a typed address.
struct MapStop: Identifiable {
    let id: Int
    let number: Int
    let title: String
    let coordinate: CLLocationCoordinate2D
    let approximate: Bool
}

/// In-app Apple Maps view of a day's stops: numbered pins joined in visiting order. No API key needed.
struct TripMapView: View {
    let stops: [MapStop]
    @State private var position: MapCameraPosition = .automatic
    @State private var selection: Int?

    var body: some View {
        Map(position: $position, selection: $selection) {
            if stops.count > 1 {
                MapPolyline(coordinates: stops.map(\.coordinate))
                    .stroke(Theme.forest.opacity(0.75), style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            }
            ForEach(stops) { stop in
                Annotation(stop.title, coordinate: stop.coordinate, anchor: .center) {
                    Text("\(stop.number)")
                        .font(.caption.bold()).foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(Theme.forest.opacity(stop.approximate ? 0.65 : 1), in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 2.5))
                        .shadow(color: .black.opacity(0.2), radius: 3, y: 1)
                        .scaleEffect(selection == stop.id ? 1.25 : 1)
                        .animation(.spring(duration: 0.25), value: selection)
                }
                .tag(stop.id)
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .mapControls { MapCompass(); MapScaleView() }
        // Re-frame whenever the day (and so the set of pins) changes.
        .onChange(of: stops.map(\.id)) { _, _ in withAnimation { position = .automatic } }
    }
}

/// Opens a Google Maps URL in the Google Maps app when installed, otherwise in Safari.
struct MapsLink<Label: View>: View {
    let url: URL?
    @ViewBuilder let label: () -> Label
    @Environment(\.openURL) private var openURL
    var body: some View {
        Button { if let url { openURL(url) } } label: { label() }.disabled(url == nil)
    }
}
