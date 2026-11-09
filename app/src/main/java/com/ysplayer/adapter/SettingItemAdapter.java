package com.ysplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ysplayer.R;
import com.ysplayer.bean.SettingItemInfo;

import java.util.ArrayList;
import java.util.List;

public class SettingItemAdapter extends RecyclerView.Adapter<SettingItemAdapter.ViewHolder> {

    private List<SettingItemInfo> items = new ArrayList<>();
    private int focusedItemIndex = -1;
    private OnItemClickListener itemClickListener;

    public interface OnItemClickListener {
        void onItemClick(int position, SettingItemInfo item);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setItems(List<SettingItemInfo> items) {
        this.items = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setFocusedItemIndex(int index) {
        int prev = this.focusedItemIndex;
        this.focusedItemIndex = index;
        if (prev >= 0 && prev < items.size()) notifyItemChanged(prev);
        if (this.focusedItemIndex >= 0 && this.focusedItemIndex < items.size())
            notifyItemChanged(this.focusedItemIndex);
    }

    public int getFocusedItemIndex() {
        return focusedItemIndex;
    }

    public void updateItemSelected(int index, boolean selected) {
        if (index >= 0 && index < items.size()) {
            items.get(index).setSelected(selected);
            notifyItemChanged(index);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setting_sub_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SettingItemInfo item = items.get(position);
        holder.tvItemName.setText(item.getName());

        if (item.isSelected() && position != focusedItemIndex) {
            holder.tvItemName.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.color_1890FF));
        } else {
            holder.tvItemName.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), android.R.color.white));
        }

        holder.tvItemCheck.setVisibility(
                item.isSelected() ? View.VISIBLE : View.INVISIBLE);

        if (position == focusedItemIndex) {
            holder.itemView.setBackgroundResource(R.drawable.bg_tab_selected);
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_tab_normal);
        }

        holder.itemView.setOnClickListener(v -> {
            if (itemClickListener != null) {
                itemClickListener.onItemClick(position, item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvItemName;
        TextView tvItemCheck;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvItemName = itemView.findViewById(R.id.tvItemName);
            tvItemCheck = itemView.findViewById(R.id.tvItemCheck);
        }
    }
}