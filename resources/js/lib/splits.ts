export type SplitWeight = { id: number; weight: number };

/** Same largest-remainder rule as the server, without floating-point money math. */
export function calculateShares(total: number, method: string, input: SplitWeight[]): Record<number, number> {
    if (!Number.isSafeInteger(total) || total < 1 || total > 100000000 || !input.length) throw new Error('Enter a positive total and select at least one traveler.');
    const rows = [...input].sort((a, b) => a.id - b.id).map(row => ({ ...row, weight: method === 'equal' ? 1 : row.weight }));
    if (rows.some(row => !Number.isSafeInteger(row.weight) || row.weight < 0 || row.weight > 100000000)) throw new Error('Enter valid non-negative amounts or whole-number shares.');
    const sum = rows.reduce((value, row) => value + row.weight, 0);
    if (sum <= 0) throw new Error('At least one share must be greater than zero.');
    if (method === 'exact') {
        if (sum !== total) throw new Error('Exact amounts must add up to the expense total.');
        return Object.fromEntries(rows.map(row => [row.id, row.weight]));
    }
    if (method === 'percentage' && sum !== 10000) throw new Error('Percentages must add up to 100%.');
    const amounts: Record<number, number> = {};
    const remainders = rows.map(row => {
        const product = BigInt(total) * BigInt(row.weight);
        amounts[row.id] = Number(product / BigInt(sum));
        return { id: row.id, remainder: product % BigInt(sum) };
    }).sort((a,b) => a.remainder === b.remainder ? a.id-b.id : a.remainder > b.remainder ? -1 : 1);
    let remaining = total - Object.values(amounts).reduce((sum, amount) => sum + amount, 0);
    for (const row of remainders) { if (remaining-- <= 0) break; amounts[row.id]++; }
    return amounts;
}
