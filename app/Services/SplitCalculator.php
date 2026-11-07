<?php

namespace App\Services;

use Illuminate\Validation\ValidationException;

class SplitCalculator
{
    /** Allocate integer minor units with deterministic largest-remainder rounding. */
    public function calculate(int $total, string $method, array $weights): array
    {
        if ($total < 1 || count($weights) === 0 || min($weights) < 0 || array_sum($weights) <= 0) {
            throw ValidationException::withMessages(['allocations' => 'Choose members and positive shares.']);
        }
        ksort($weights, SORT_NUMERIC);
        if ($method === 'equal') { $weights = array_fill_keys(array_keys($weights), 1); }
        if ($method === 'exact') {
            if (array_sum($weights) !== $total) { throw ValidationException::withMessages(['allocations' => 'Exact amounts must equal the expense total.']); }
            return $weights;
        }
        if ($method === 'percentage' && array_sum($weights) !== 10000) {
            throw ValidationException::withMessages(['allocations' => 'Percentages must total 100%.']);
        }
        $sum = array_sum($weights); $result = []; $remainders = [];
        foreach ($weights as $member => $weight) {
            $result[$member] = intdiv($total * $weight, $sum);
            $remainders[$member] = ($total * $weight) % $sum;
        }
        arsort($remainders, SORT_NUMERIC);
        $remaining = $total - array_sum($result);
        foreach (array_keys($remainders) as $member) {
            if ($remaining-- <= 0) { break; }
            $result[$member]++;
        }
        return $result;
    }
}
