<?php

namespace App\Enums;

enum ExpenseStatus: string
{
    case Draft = 'draft';
    case Open = 'open';
    case Completed = 'completed';
    case Closed = 'closed';
    case Cancelled = 'cancelled';

    public function countsTowardBalance(): bool
    {
        return in_array($this, [self::Open, self::Completed, self::Closed], true);
    }
}
