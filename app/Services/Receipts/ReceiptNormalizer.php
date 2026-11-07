<?php

namespace App\Services\Receipts;

use Illuminate\Support\Collection;

/**
 * Turns Gemini's reading of a receipt into an expense draft whose lines add up exactly to the total,
 * as itemized splits require. Charges added on top (service charge, tax not in item prices) become
 * their own lines so the group can see and assign them; discounts are spread across the items.
 */
class ReceiptNormalizer
{
    /**
     * @param  array  $raw  Gemini JSON (amounts in major units)
     * @param  Collection<int, \App\Models\ExpenseCategory>  $categories
     */
    public function normalize(array $raw, string $tripCurrency, Collection $categories): array
    {
        $minor = fn ($value) => is_numeric($value) ? (int) round(((float) $value) * 100) : null;
        $warnings = [];

        $items = collect($raw['items'] ?? [])
            ->map(fn ($item) => ['description' => $this->clean($item['description'] ?? ''), 'quantity' => is_numeric($item['quantity'] ?? null) ? (float) $item['quantity'] : null, 'amount_minor' => $minor($item['amount'] ?? null)])
            ->filter(fn ($item) => $item['description'] !== '' && $item['amount_minor'] !== null && $item['amount_minor'] > 0)
            ->map(fn ($item) => ['description' => $item['quantity'] && $item['quantity'] > 1 && ! preg_match('/^\d+\s*x\b/i', $item['description']) ? rtrim(rtrim(number_format($item['quantity'], 2, '.', ''), '0'), '.').' × '.$item['description'] : $item['description'], 'amount_minor' => $item['amount_minor']])
            ->values()->all();

        $service = max(0, $minor($raw['service_charge'] ?? null) ?? 0);
        $tax = max(0, $minor($raw['tax_added'] ?? null) ?? 0);
        $discount = abs($minor($raw['discount'] ?? null) ?? 0);
        $itemSum = array_sum(array_column($items, 'amount_minor'));
        $total = $minor($raw['total'] ?? null);
        if (! $total || $total <= 0) {
            $total = $itemSum + $service + $tax - $discount;
            if ($total > 0) { $warnings[] = 'No total was found on the receipt, so it was added up from the items.'; }
        }

        // Spread the discount across items in proportion to their price.
        if ($discount > 0 && $itemSum > 0) {
            $left = $discount;
            foreach ($items as $i => $item) {
                $share = $i === array_key_last($items) ? $left : (int) floor($discount * $item['amount_minor'] / $itemSum);
                $items[$i]['amount_minor'] = max(1, $item['amount_minor'] - $share);
                $left -= $share;
            }
        }

        $lines = $items;
        if ($service > 0) { $lines[] = ['description' => 'Service charge', 'amount_minor' => $service]; }
        if ($tax > 0) { $lines[] = ['description' => 'Tax', 'amount_minor' => $tax]; }

        // Small gaps are rounding; put them on the largest line. Bigger ones need a human look.
        $gap = ($total ?? 0) - array_sum(array_column($lines, 'amount_minor'));
        if ($lines && $gap !== 0) {
            if (abs($gap) <= max(100, (int) round($total * 0.02))) {
                $largest = array_keys(array_column($lines, 'amount_minor'), max(array_column($lines, 'amount_minor')))[0];
                $lines[$largest]['amount_minor'] = max(1, $lines[$largest]['amount_minor'] + $gap);
            } else {
                $warnings[] = 'The items add up to '.number_format(array_sum(array_column($lines, 'amount_minor')) / 100, 2).' but the receipt total is '.number_format($total / 100, 2).'. Check for missing or misread items.';
            }
        }
        if (! $lines) { $warnings[] = 'No line items could be read. The total is filled in; you can still split it equally.'; }

        $currency = strtoupper((string) ($raw['currency'] ?? ''));
        if ($currency !== '' && $currency !== strtoupper($tripCurrency)) {
            $warnings[] = "This receipt looks like it is in {$currency}, but the trip uses {$tripCurrency}. Convert the amounts before saving.";
        }

        $merchant = $this->clean($raw['merchant'] ?? '');
        $date = is_string($raw['date'] ?? null) && preg_match('/^\d{4}-\d{2}-\d{2}$/', $raw['date']) && strtotime($raw['date']) ? $raw['date'] : null;

        return [
            'merchant' => $merchant ?: null,
            'title' => $merchant ?: 'Receipt',
            'incurred_at' => $date,
            'currency' => $currency ?: null,
            'category_id' => $this->category($raw['category'] ?? null, $categories),
            'total_minor' => $total > 0 ? $total : null,
            'lines' => array_values($lines),
            'warnings' => array_values(array_merge($warnings, array_filter([is_string($raw['notes'] ?? null) ? $this->clean($raw['notes']) : null]))),
        ];
    }

    private function category(?string $hint, Collection $categories): ?int
    {
        if (! $hint) { return null; }
        $hint = strtolower($hint);
        $aliases = ['food' => ['food', 'meal', 'dining', 'restaurant'], 'transport' => ['transport', 'transportation', 'travel', 'fuel', 'gas'], 'lodging' => ['lodging', 'accommodation', 'hotel', 'stay'], 'activities' => ['activities', 'activity', 'tour', 'entertainment', 'tickets'], 'shopping' => ['shopping', 'souvenirs', 'groceries']];
        $words = $aliases[$hint] ?? [$hint];
        $match = $categories->first(fn ($category) => collect($words)->contains(fn ($word) => str_contains(strtolower($category->name), $word)));
        return $match?->id;
    }

    private function clean(mixed $text): string
    {
        return mb_substr(trim(preg_replace('/\s+/u', ' ', (string) $text)), 0, 200);
    }
}
