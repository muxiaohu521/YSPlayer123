package com.ysplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ysplayer.R;
import com.ysplayer.bean.SettingGroup;

import java.util.ArrayList;
import java.util.List;

public class SettingGroupAdapter extends RecyclerView.Adapter<SettingGroupAdapter.ViewHolder> {

    private List<SettingGroup> groups = new ArrayList<>();
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;
    private OnGroupSelectListener listener;

    public interface OnGroupSelectListener {
        void onGroupSelected(int groupIndex);
    }

    public void setOnGroupSelectListener(OnGroupSelectListener listener) {
        this.listener = listener;
    }

    public void setGroups(List<SettingGroup> groups) {
        this.groups = groups != null ? groups : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setSelectedGroupIndex(int groupIndex) {
        int prev = this.selectedGroupIndex;
        this.selectedGroupIndex = groupIndex;
        int prevPos = findPositionByGroupIndex(prev);
        if (prevPos >= 0) notifyItemChanged(prevPos);
        int curPos = findPositionByGroupIndex(groupIndex);
        if (curPos >= 0) notifyItemChanged(curPos);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int groupIndex) {
        int prev = this.focusedGroupIndex;
        this.focusedGroupIndex = groupIndex;
        int prevPos = findPositionByGroupIndex(prev);
        if (prevPos >= 0) notifyItemChanged(prevPos);
        int curPos = findPositionByGroupIndex(groupIndex);
        if (curPos >= 0) notifyItemChanged(curPos);
    }

    public int getFocusedGroupIndex() {
        return focusedGroupIndex;
    }

    public int findPositionByGroupIndex(int groupIndex) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).getGroupIndex() == groupIndex) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setting_group_tab, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SettingGroup group = groups.get(position);
        holder.tvGroupName.setText(group.getGroupName());

        boolean isSelected = group.getGroupIndex() == selectedGroupIndex;
        boolean isFocused = group.getGroupIndex() == focusedGroupIndex;

        if (isSelected || isFocused) {
            holder.tvGroupName.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.color_1890FF));
        } else {
            holder.tvGroupName.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.white));
        }

        if (isFocused) {
            holder.itemView.setBackgroundResource(R.drawable.bg_tab_selected);
        } else {
            holder.itemView.setBackgroundResource(android.R.color.transparent);
        }

        holder.tvGroupArrow.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);

        final int groupIndex = group.getGroupIndex();
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onGroupSelected(groupIndex);
            }
        });
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGroupName;
        View tvGroupArrow;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvGroupName = itemView.findViewById(R.id.tvGroupName);
            tvGroupArrow = itemView.findViewById(R.id.tvGroupArrow);
        }
    }
}