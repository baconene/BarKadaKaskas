/** Currencies a trip can use (kept in sync with TripController's validation). */
export const CURRENCIES = ['PHP', 'USD', 'EUR', 'GBP', 'SGD', 'AUD', 'CAD', 'JPY', 'KRW', 'THB', 'VND', 'IDR', 'MYR', 'HKD', 'TWD', 'CNY', 'AED', 'NZD', 'CHF'] as const;

const EURO = ['AT', 'BE', 'CY', 'DE', 'EE', 'ES', 'FI', 'FR', 'GR', 'HR', 'IE', 'IT', 'LT', 'LU', 'LV', 'MT', 'NL', 'PT', 'SI', 'SK'];
const CURRENCY_BY_COUNTRY: Record<string, string> = {
    PH: 'PHP', US: 'USD', GB: 'GBP', SG: 'SGD', AU: 'AUD', CA: 'CAD', JP: 'JPY', KR: 'KRW', TH: 'THB', VN: 'VND', ID: 'IDR',
    MY: 'MYR', HK: 'HKD', TW: 'TWD', CN: 'CNY', AE: 'AED', NZ: 'NZD', CH: 'CHF', ...Object.fromEntries(EURO.map(c => [c, 'EUR'])),
};
const ZONE_BY_COUNTRY: Record<string, string> = {
    PH: 'Asia/Manila', SG: 'Asia/Singapore', JP: 'Asia/Tokyo', KR: 'Asia/Seoul', TH: 'Asia/Bangkok', VN: 'Asia/Ho_Chi_Minh', MY: 'Asia/Kuala_Lumpur',
    HK: 'Asia/Hong_Kong', TW: 'Asia/Taipei', CN: 'Asia/Shanghai', AE: 'Asia/Dubai', KH: 'Asia/Phnom_Penh', LA: 'Asia/Vientiane', MM: 'Asia/Yangon',
    IN: 'Asia/Kolkata', MV: 'Indian/Maldives', QA: 'Asia/Qatar', SA: 'Asia/Riyadh', TR: 'Europe/Istanbul', GB: 'Europe/London', IE: 'Europe/Dublin',
    FR: 'Europe/Paris', DE: 'Europe/Berlin', IT: 'Europe/Rome', ES: 'Europe/Madrid', NL: 'Europe/Amsterdam', BE: 'Europe/Brussels', PT: 'Europe/Lisbon',
    CH: 'Europe/Zurich', AT: 'Europe/Vienna', GR: 'Europe/Athens', CZ: 'Europe/Prague', NZ: 'Pacific/Auckland', GU: 'Pacific/Guam',
};

/** Countries spanning several time zones: pick by longitude. */
function zoneByLongitude(country: string, lng: number, lat: number): string | null {
    switch (country) {
        case 'US': return lat < 23 && lng < -154 ? 'Pacific/Honolulu' : lng < -141 ? 'America/Anchorage' : lng < -114.5 ? 'America/Los_Angeles' : lng < -101 ? 'America/Denver' : lng < -86.5 ? 'America/Chicago' : 'America/New_York';
        case 'CA': return lng < -120 ? 'America/Vancouver' : lng < -102 ? 'America/Edmonton' : lng < -90 ? 'America/Winnipeg' : lng < -64 ? 'America/Toronto' : 'America/Halifax';
        case 'AU': return lng < 129 ? 'Australia/Perth' : lng < 138 ? 'Australia/Darwin' : lat > -29 && lng < 153.6 ? 'Australia/Brisbane' : lng < 141 ? 'Australia/Adelaide' : 'Australia/Sydney';
        case 'ID': return lng < 115 ? 'Asia/Jakarta' : lng < 129 ? 'Asia/Makassar' : 'Asia/Jayapura';
        default: return null;
    }
}

/** Best-guess currency and time zone for a destination; null fields mean "leave as is". */
export function defaultsFor(country: string | null, lat: number | null, lng: number | null): { currency: string | null; timezone: string | null } {
    if (!country) return { currency: null, timezone: null };
    const code = country.toUpperCase();
    const zone = (lat !== null && lng !== null ? zoneByLongitude(code, lng, lat) : null) ?? ZONE_BY_COUNTRY[code] ?? null;
    return { currency: CURRENCY_BY_COUNTRY[code] ?? null, timezone: zone };
}

/** Time zones offered in pickers: common travel zones plus any already chosen. */
export const COMMON_ZONES = ['Asia/Manila', 'Asia/Singapore', 'Asia/Tokyo', 'Asia/Seoul', 'Asia/Hong_Kong', 'Asia/Taipei', 'Asia/Bangkok', 'Asia/Ho_Chi_Minh', 'Asia/Kuala_Lumpur', 'Asia/Jakarta', 'Asia/Makassar', 'Asia/Shanghai', 'Asia/Dubai', 'Australia/Sydney', 'Australia/Perth', 'Pacific/Auckland', 'Europe/London', 'Europe/Paris', 'America/Los_Angeles', 'America/New_York', 'UTC'];
