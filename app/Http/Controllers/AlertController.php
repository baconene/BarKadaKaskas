<?php

namespace App\Http\Controllers;

use App\Models\Alert;
use Illuminate\Http\Request;

/** Your alerts across your trips: newest first, with the unread count; mark some or all as read. */
class AlertController extends Controller
{
    public function index(Request $request)
    {
        $request->validate(['after' => 'nullable|integer|min:0']);
        $query = Alert::where('user_id', $request->user()->id)->with('trip:id,name');
        // ?after=<id> returns only newer alerts (used by the mobile app's background check).
        if ($after = $request->integer('after')) { $query->where('id', '>', $after); }
        $alerts = $query->orderByDesc('id')->limit(50)->get();
        return response()->json([
            'data' => $alerts->map(fn (Alert $a) => [
                'id' => $a->id, 'type' => $a->type, 'title' => $a->title, 'body' => $a->body, 'trip_id' => $a->trip_id,
                'trip_name' => $a->trip?->name, 'data' => $a->data ?? (object) [], 'read' => $a->read_at !== null,
                'created_at' => $a->created_at?->toIso8601String(),
            ])->values(),
            'unread_count' => Alert::where('user_id', $request->user()->id)->whereNull('read_at')->count(),
        ]);
    }

    public function unreadCount(Request $request)
    {
        return response()->json(['unread_count' => Alert::where('user_id', $request->user()->id)->whereNull('read_at')->count()]);
    }

    /** Marks the given alert ids (or all, without ids) as read. */
    public function read(Request $request)
    {
        $data = $request->validate(['ids' => 'nullable|array|max:200', 'ids.*' => 'integer']);
        Alert::where('user_id', $request->user()->id)->whereNull('read_at')
            ->when(! empty($data['ids']), fn ($q) => $q->whereIn('id', $data['ids']))
            ->update(['read_at' => now()]);
        return $this->unreadCount($request);
    }
}
