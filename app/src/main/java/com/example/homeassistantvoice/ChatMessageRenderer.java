package com.example.homeassistantvoice;

import org.json.JSONObject;

final class ChatMessageRenderer {
    private ChatMessageRenderer() {
    }

    static String render(JSONObject aiResponse) {
        if (aiResponse == null) {
            return "";
        }
        String action = aiResponse.optString("action", "");
        String title = aiResponse.optString("title", "");
        String date = aiResponse.optString("date", "");
        String time = aiResponse.optString("start_time", "");
        String message = aiResponse.optString("message", "");

        if ("propose_add_event".equals(action) || "add_event".equals(action)) {
            return compact("Calendar proposal", date, time, title, message);
        }
        if ("propose_add_task".equals(action) || "add_task".equals(action)) {
            return compact("Task proposal", "", "", title, message);
        }
        if ("propose_delete_event".equals(action) || "delete_event".equals(action)) {
            return compact("Delete event proposal", date, time, title, message);
        }
        if ("propose_delete_task".equals(action) || "delete_task".equals(action)) {
            return compact("Delete task proposal", "", "", title, message);
        }
        if ("query_events".equals(action) || "query_tasks".equals(action) || "question".equals(action) || "message".equals(action)) {
            return message;
        }
        return message.isEmpty() ? aiResponse.toString() : message;
    }

    private static String compact(String prefix, String date, String time, String title, String message) {
        StringBuilder out = new StringBuilder(prefix);
        if (date != null && !date.isEmpty()) {
            out.append(" · ").append(date);
        }
        if (time != null && !time.isEmpty()) {
            out.append(" ").append(time);
        }
        if (title != null && !title.isEmpty()) {
            out.append(" · ").append(title);
        }
        if (message != null && !message.isEmpty()) {
            out.append("\n").append(message);
        }
        return out.toString();
    }
}
