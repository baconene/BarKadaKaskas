<?php

namespace Tests\Unit;

use App\Services\Receipts\ReceiptNormalizer;
use Illuminate\Support\Collection;
use PHPUnit\Framework\TestCase;

class ReceiptNormalizerTest extends TestCase
{
    private function categories(): Collection
    {
        return collect([(object) ['id' => 1, 'name' => 'Food & drinks'], (object) ['id' => 2, 'name' => 'Transport']]);
    }

    public function test_lines_add_up_with_service_charge_and_quantities(): void
    {
        $draft = (new ReceiptNormalizer)->normalize([
            'merchant' => '  Baha Ba\'r ', 'date' => '2026-11-12', 'currency' => 'PHP', 'category' => 'food',
            'items' => [['description' => 'Sisig', 'quantity' => 2, 'amount' => 560], ['description' => 'Iced tea', 'quantity' => 1, 'amount' => 120.5]],
            'service_charge' => 68.05, 'tax_added' => 0, 'discount' => null, 'total' => 748.55,
        ], 'PHP', $this->categories());

        $this->assertSame('Baha Ba\'r', $draft['title']);
        $this->assertSame('2026-11-12', $draft['incurred_at']);
        $this->assertSame(1, $draft['category_id']);
        $this->assertSame(74855, $draft['total_minor']);
        $this->assertSame(['2 × Sisig', 'Iced tea', 'Service charge'], array_column($draft['lines'], 'description'));
        $this->assertSame(74855, array_sum(array_column($draft['lines'], 'amount_minor')));
        $this->assertSame([], $draft['warnings']);
    }

    public function test_discount_is_spread_and_rounding_is_absorbed(): void
    {
        $draft = (new ReceiptNormalizer)->normalize([
            'items' => [['description' => 'Room', 'amount' => 1000], ['description' => 'Breakfast', 'amount' => 333.33]],
            'discount' => 133.33, 'total' => 1200.01, 'category' => 'lodging',
        ], 'PHP', $this->categories());
        $this->assertSame(120001, array_sum(array_column($draft['lines'], 'amount_minor')));
        $this->assertNull($draft['category_id']);
    }

    public function test_big_mismatch_and_other_currency_are_flagged_not_hidden(): void
    {
        $draft = (new ReceiptNormalizer)->normalize([
            'items' => [['description' => 'Ramen', 'amount' => 1200]], 'total' => 3000, 'currency' => 'JPY', 'category' => 'food',
        ], 'PHP', $this->categories());
        $this->assertSame(120000, array_sum(array_column($draft['lines'], 'amount_minor')));
        $this->assertCount(2, $draft['warnings']);
        $this->assertStringContainsString('JPY', $draft['warnings'][1]);
    }

    public function test_total_only_receipt_still_fills_the_total(): void
    {
        $draft = (new ReceiptNormalizer)->normalize(['items' => [], 'total' => 450, 'date' => 'yesterday', 'category' => 'transport'], 'PHP', $this->categories());
        $this->assertSame(45000, $draft['total_minor']);
        $this->assertSame([], $draft['lines']);
        $this->assertNull($draft['incurred_at']);
        $this->assertSame(2, $draft['category_id']);
    }
}
