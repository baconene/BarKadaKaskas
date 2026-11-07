/** Google Maps universal search links work without an API key on web and mobile. */
export function googleMapsUrl(destination: string): string {
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(destination)}`;
}
