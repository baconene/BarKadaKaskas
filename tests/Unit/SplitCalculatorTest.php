<?php
namespace Tests\Unit;

use App\Services\SplitCalculator;
use Illuminate\Validation\ValidationException;
use Tests\TestCase;

class SplitCalculatorTest extends TestCase
{
    public function test_equal_rounding_is_deterministic_and_preserves_every_cent(): void
    {
        $this->assertSame([1 => 34, 2 => 33, 3 => 33], (new SplitCalculator)->calculate(100, 'equal', [3 => 1, 1 => 1, 2 => 1]));
    }
    public function test_percentages_and_shares(): void
    {
        $calculator = new SplitCalculator;
        $this->assertSame([1 => 600, 2 => 400], $calculator->calculate(1000, 'percentage', [1 => 6000, 2 => 4000]));
        $this->assertSame([1 => 667, 2 => 333], $calculator->calculate(1000, 'shares', [1 => 2, 2 => 1]));
    }
    public function test_incomplete_exact_split_is_rejected(): void
    {
        $this->expectException(ValidationException::class);
        (new SplitCalculator)->calculate(1000, 'exact', [1 => 300, 2 => 600]);
    }
}
