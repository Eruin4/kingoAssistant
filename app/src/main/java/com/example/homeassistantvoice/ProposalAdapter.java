package com.example.homeassistantvoice;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ProposalAdapter extends RecyclerView.Adapter<ProposalAdapter.ProposalViewHolder> {
    interface Listener {
        void onAcceptProposal(String proposalId);

        void onRejectProposal(String proposalId);
    }

    private final List<JSONObject> proposals = new ArrayList<>();
    private final Listener listener;

    ProposalAdapter(Listener listener) {
        this.listener = listener;
    }

    void setProposals(List<JSONObject> nextProposals) {
        proposals.clear();
        proposals.addAll(nextProposals);
        notifyDataSetChanged();
    }

    @Override
    public ProposalViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout row = TaskAdapter.RowViews.itemShell(parent);
        TextView body = TaskAdapter.RowViews.text(row, 15, Color.rgb(238, 243, 247), false);
        LinearLayout actions = new LinearLayout(parent.getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button accept = button(parent, "Accept", Color.rgb(55, 192, 150), Color.rgb(8, 20, 18));
        Button reject = button(parent, "Reject", Color.rgb(239, 83, 80), Color.WHITE);
        actions.addView(accept, weightParams());
        actions.addView(reject, weightParams());
        row.addView(body);
        row.addView(actions);
        return new ProposalViewHolder(row, body, accept, reject);
    }

    @Override
    public void onBindViewHolder(ProposalViewHolder holder, int position) {
        JSONObject proposal = proposals.get(position);
        String proposalId = proposal.optString("proposal_id", "");
        holder.body.setText(ChatMessageRenderer.render(proposal));
        holder.accept.setOnClickListener(v -> listener.onAcceptProposal(proposalId));
        holder.reject.setOnClickListener(v -> listener.onRejectProposal(proposalId));
    }

    @Override
    public int getItemCount() {
        return proposals.size();
    }

    private static Button button(ViewGroup parent, String text, int backgroundColor, int textColor) {
        Button button = new Button(parent.getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(backgroundColor);
        bg.setCornerRadius(dp(parent, 8));
        button.setBackground(bg);
        return button;
    }

    private static LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(4, 8, 4, 0);
        return params;
    }

    private static int dp(ViewGroup parent, int value) {
        return (int) (value * parent.getResources().getDisplayMetrics().density + 0.5f);
    }

    static final class ProposalViewHolder extends RecyclerView.ViewHolder {
        final TextView body;
        final Button accept;
        final Button reject;

        ProposalViewHolder(View itemView, TextView body, Button accept, Button reject) {
            super(itemView);
            this.body = body;
            this.accept = accept;
            this.reject = reject;
        }
    }
}
