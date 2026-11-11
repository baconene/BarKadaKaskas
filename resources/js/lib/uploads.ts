/** Shrinks a photo in the browser (longest side `maxSide`, JPEG) so uploads stay quick on mobile data. */
export async function shrinkImage(file: File, maxSide = 2000, quality = 0.88): Promise<Blob> {
    if (!/^image\//.test(file.type)) return file;
    const bitmap = await createImageBitmap(file).catch(() => null);
    if (!bitmap) return file;
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.type === 'image/jpeg' && file.size < 2_000_000) return file;
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale); canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return await new Promise<Blob>(resolve => canvas.toBlob(blob => resolve(blob ?? file), 'image/jpeg', quality));
}

function xsrfToken(): string {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
}

/** JSON request to the app's own web routes, with the session's CSRF token. Throws an Error with a readable message. */
export async function appFetch<T = unknown>(url: string, options: { method?: string; body?: FormData } = {}): Promise<T> {
    const response = await fetch(url, { method: options.method ?? 'GET', body: options.body, credentials: 'same-origin', headers: { Accept: 'application/json', 'X-XSRF-TOKEN': xsrfToken(), 'X-Requested-With': 'XMLHttpRequest' } });
    if (response.status === 204) return undefined as T;
    const json = await response.json().catch(() => ({}));
    if (!response.ok) {
        const firstError = json.errors ? Object.values(json.errors as Record<string, string[]>)[0]?.[0] : undefined;
        throw new Error(response.status === 419 ? 'Your session expired. Refresh the page and try again.' : firstError ?? json.message ?? ([502, 504].includes(response.status) ? `The server couldn’t finish that (HTTP ${response.status}). Please try again in a moment.` : `Something went wrong (HTTP ${response.status}).`));
    }
    return json as T;
}

/** Saves an edited JPEG as the signed-in traveller's profile picture (Inertia reloads the page props). */
export async function saveAvatar(photo: Blob, onError?: (message: string) => void): Promise<void> {
    const { router } = await import('@inertiajs/vue3');
    router.post('/me/avatar', { avatar: new File([photo], 'avatar.jpg', { type: 'image/jpeg' }) }, {
        forceFormData: true, preserveScroll: true, preserveState: true,
        onError: errors => onError?.(Object.values(errors).join(' ')),
    });
}
