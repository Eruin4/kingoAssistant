package com.example.homeassistantvoice;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {
    private final List<JSONObject> tasks = new ArrayList<>();

    void setTasks(List<JSONObject> nextTasks) {
        tasks.clear();
        tasks.addAll(nextTasks);
        notifyDataSetChanged();
    }

    String getTaskId(int position) {
        if (position < 0 || position >= tasks.size()) {
            return "";
        }
        return tasks.get(position).optString("id", "");
    }

    void removeItem(int position) {
        if (position < 0 || position >= tasks.size()) {
            return;
        }
        tasks.remove(position);
        notifyItemRemoved(position);
    }

    void restoreItem(JSONObject task, int position) {
        int safePosition = Math.max(0, Math.min(position, tasks.size()));
        tasks.add(safePosition, task);
        notifyItemInserted(safePosition);
    }

    JSONObject getItem(int position) {
        if (position < 0 || position >= tasks.size()) {
            return null;
        }
        return tasks.get(position);
    }

    @Override
    public TaskViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout row = RowViews.itemShell(parent);
        return new TaskViewHolder(row);
    }

    @Override
    public void onBindViewHolder(TaskViewHolder holder, int position) {
        JSONObject task = tasks.get(position);
        holder.title.setText(task.optString("title", "Untitled"));
        String memo = task.optString("memo", "");
        holder.memo.setText(memo);
        holder.memo.setVisibility(memo.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    static final class TaskViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView memo;

        TaskViewHolder(LinearLayout row) {
            super(row);
            title = RowViews.text(row, 17, Color.rgb(238, 243, 247), true);
            memo = RowViews.text(row, 14, Color.rgb(153, 166, 178), false);
            row.addView(title);
            row.addView(memo);
        }
    }

    static final class RowViews {
        private RowViews() {
        }

        static LinearLayout itemShell(ViewGroup parent) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(parent, 18), dp(parent, 14), dp(parent, 18), dp(parent, 14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(33, 41, 49));
            bg.setCornerRadius(dp(parent, 8));
            row.setBackground(bg);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(parent, 10));
            row.setLayoutParams(params);
            return row;
        }

        static TextView text(ViewGroup parent, int size, int color, boolean bold) {
            TextView view = new TextView(parent.getContext());
            view.setTextSize(size);
            view.setTextColor(color);
            view.setLineSpacing(2, 1.05f);
            if (bold) {
                view.setTypeface(Typeface.DEFAULT_BOLD);
            }
            return view;
        }

        private static int dp(ViewGroup parent, int value) {
            return (int) (value * parent.getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
