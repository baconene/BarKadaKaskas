<?php

namespace App\Services\Vertex;

use RuntimeException;

/** Vertex AI is not set up, or its credentials were refused. `detail` is for logs only. */
class VertexException extends RuntimeException
{
    public function __construct(string $message, public readonly ?string $detail = null)
    {
        parent::__construct($message);
    }
}
