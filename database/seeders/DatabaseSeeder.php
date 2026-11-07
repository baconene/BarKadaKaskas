<?php

namespace Database\Seeders;

use App\Models\ExpenseCategory;
// use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;

class DatabaseSeeder extends Seeder
{
    /**
     * Seed the application's database.
     */
    public function run(): void
    {
        foreach (['Accommodation', 'Flights and intercity travel', 'Local transportation', 'Vehicle expenses', 'Food and drinks', 'Groceries and supplies', 'Activities and attractions', 'Equipment rental', 'Travel documents and fees', 'Insurance and medical', 'Connectivity', 'Shopping and souvenirs', 'Miscellaneous'] as $index => $name) {
            ExpenseCategory::firstOrCreate(['trip_id' => null, 'name' => $name], ['sort_order' => $index]);
        }
    }
}
