<?php

namespace App\Services\Receipts;

use RuntimeException;

/** A receipt could not be read. The message is safe to show to the traveller. */
class ReceiptReadException extends RuntimeException
{
    public function __construct(string $message, public readonly int $status = 422, public readonly ?string $detail = null)
    {
        parent::__construct($message);
    }
}
