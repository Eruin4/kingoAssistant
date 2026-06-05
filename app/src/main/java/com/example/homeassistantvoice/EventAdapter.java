package com.example.homeassistantvoice;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class EventAdapter extends RecyclerView.Adapter<EventAdapter.EventViewHolder> {
    interface Listener {
        void onDeleteEvent(String eventId);
    }

    private final List<JSONObject> events = new ArrayList<>();
    private final Listener listener;

    EventAdapter(Listener listener) {
        this.listener = listener;
    }

    void setEvents(List<JSONObject> nextEvents) {
        events.clear();
        events.addAll(nextEvents);
        notifyDataSetChanged();
    }

    String getEventId(int position) {
        if (position < 0 || position >= events.size()) {
            return "";
        }
        return events.get(position).optString("id", "");
    }

    void removeItem(int position) {
        if (position < 0 || position >= events.size()) {
            return;
        }
        events.remove(position);
        notifyItemRemoved(position);
    }

    void restoreItem(JSONObject event, int position) {
        int safePosition = Math.max(0, Math.min(position, events.size()));
        events.add(safePosition, event);
        notifyItemInserted(safePosition);
    }

    JSONObject getItem(int position) {
        if (position < 0 || position >= events.size()) {
            return null;
        }
        return events.get(position);
    }

    @Override
    public EventViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout row = TaskAdapter.RowViews.itemShell(parent);
        return new EventViewHolder(row);
    }

    @Override
    public void onBindViewHolder(EventViewHolder holder, int position) {
        JSONObject event = events.get(position);
        holder.title.setText(event.optString("title", "Untitled"));
        holder.meta.setText(event.optString("calendar", "calendar_1") + "  "
                + event.optString("date", "") + " " + event.optString("start_time", "")
                + timeSuffix(event));
        String memo = event.optString("memo", "");
        holder.memo.setText(memo);
        holder.memo.setVisibility(memo.isEmpty() ? View.GONE : View.VISIBLE);
        holder.itemView.setOnLongClickListener(v -> {
            listener.onDeleteEvent(event.optString("id", ""));
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    private String timeSuffix(JSONObject event) {
        String end = event.optString("end_time", "");
        return end.isEmpty() ? "" : "-" + end;
    }

    static final class EventViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;
        final TextView memo;

        EventViewHolder(LinearLayout row) {
            super(row);
            title = TaskAdapter.RowViews.text(row, 17, Color.rgb(238, 243, 247), true);
            meta = TaskAdapter.RowViews.text(row, 13, Color.rgb(153, 166, 178), false);
            memo = TaskAdapter.RowViews.text(row, 14, Color.rgb(238, 243, 247), false);
            row.addView(title);
            row.addView(meta);
            row.addView(memo);
        }
    }
}
