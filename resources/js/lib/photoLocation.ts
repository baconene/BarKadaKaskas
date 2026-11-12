/**
 * Where and when a photo was taken, read from its EXIF data in the browser (photos are resized before upload, which
 * drops EXIF, so this runs on the original file). Only JPEGs carry it; anything else returns nulls.
 */
export type PhotoLocation = { latitude: number | null; longitude: number | null; taken_at: string | null };

export async function readPhotoLocation(file: Blob): Promise<PhotoLocation> {
    const none: PhotoLocation = { latitude: null, longitude: null, taken_at: null };
    try {
        const view = new DataView(await file.slice(0, 256 * 1024).arrayBuffer());
        if (view.getUint16(0) !== 0xffd8) return none;
        let offset = 2;
        while (offset + 4 < view.byteLength) {
            const marker = view.getUint16(offset); const size = view.getUint16(offset + 2);
            if (marker === 0xffe1 && view.getUint32(offset + 4) === 0x45786966) return parseTiff(view, offset + 10) ?? none; // "Exif"
            if ((marker & 0xff00) !== 0xff00) break;
            offset += 2 + size;
        }
    } catch { /* unreadable: no location */ }
    return none;
}

function parseTiff(view: DataView, start: number): PhotoLocation | null {
    const little = view.getUint16(start) === 0x4949;
    const u16 = (o: number) => view.getUint16(start + o, little), u32 = (o: number) => view.getUint32(start + o, little);
    const tags = (ifd: number) => { const out = new Map<number, number>(); const n = u16(ifd); for (let i = 0; i < n; i++) out.set(u16(ifd + 2 + i * 12), ifd + 2 + i * 12); return out; };
    const rational = (o: number) => { const den = u32(o + 4); return den ? u32(o) / den : 0; };
    const ascii = (entry: number) => { const count = u32(entry + 4); const at = count > 4 ? u32(entry + 8) : entry + 8; let s = ''; for (let i = 0; i < count - 1; i++) s += String.fromCharCode(view.getUint8(start + at + i)); return s; };
    const ifd0 = tags(u32(4));
    let latitude: number | null = null, longitude: number | null = null, taken: string | null = null;
    const gpsEntry = ifd0.get(0x8825);
    if (gpsEntry !== undefined) {
        const gps = tags(u32(gpsEntry + 8));
        const coord = (tag: number, refTag: number, negative: string) => {
            const entry = gps.get(tag), ref = gps.get(refTag); if (entry === undefined) return null;
            const at = u32(entry + 8); const value = rational(at) + rational(at + 8) / 60 + rational(at + 16) / 3600;
            return ref !== undefined && String.fromCharCode(view.getUint8(start + ref + 8)) === negative ? -value : value;
        };
        latitude = coord(2, 1, 'S'); longitude = coord(4, 3, 'W');
        if (latitude === 0 && longitude === 0) latitude = longitude = null;
    }
    const exifEntry = ifd0.get(0x8769);
    if (exifEntry !== undefined) {
        const original = tags(u32(exifEntry + 8)).get(0x9003);
        const value = original !== undefined ? ascii(original) : '';
        if (/^\d{4}:\d\d:\d\d \d\d:\d\d:\d\d$/.test(value)) taken = value.replace(/^(\d{4}):(\d\d):(\d\d)/, '$1-$2-$3');
    }
    return { latitude, longitude, taken_at: taken };
}

/** The device's current position (if allowed), for photos without their own location. Never waits more than 6 s. */
export function currentPosition(): Promise<{ latitude: number; longitude: number } | null> {
    if (typeof navigator === 'undefined' || !navigator.geolocation) return Promise.resolve(null);
    return new Promise(resolve => {
        const timer = setTimeout(() => resolve(null), 6000);
        navigator.geolocation.getCurrentPosition(
            p => { clearTimeout(timer); resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }); },
            () => { clearTimeout(timer); resolve(null); },
            { enableHighAccuracy: true, maximumAge: 10 * 60 * 1000, timeout: 5500 },
        );
    });
}
