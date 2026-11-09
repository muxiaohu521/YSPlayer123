package com.ysplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.ysplayer.R;
import com.ysplayer.bean.ChannelInfo;

import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<ChannelInfo> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ChannelInfo>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChannelInfo oldItem,
                        @NonNull ChannelInfo newItem) {
                    return oldItem.getName().equals(newItem.getName())
                            && oldItem.getChannelNum() == newItem.getChannelNum();
                }

                @Override
                public boolean areContentsTheSame(@NonNull ChannelInfo oldItem,
                        @NonNull ChannelInfo newItem) {
                    return oldItem.getRouteCount() == newItem.getRouteCount()
                            && oldItem.getCurrentRouteIndex() == newItem.getCurrentRouteIndex();
                }
            };

    private final AsyncListDiffer<ChannelInfo> mDiffer = new AsyncListDiffer<>(this, DIFF_CALLBACK);
    private int selectedPosition = -1;
    private int focusedPosition = -1;
    private OnChannelClickListener listener;

    public interface OnChannelClickListener {
        void onChannelClick(ChannelInfo channel, int position);
    }

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChannelInfo channel = mDiffer.getCurrentList().get(position);

        holder.tvNum.setText(String.valueOf(channel.getChannelNum()));
        holder.tvName.setText(channel.getName());

        int routeCount = channel.getRouteCount();
        if (routeCount > 1) {
            holder.tvRouteBadge.setText(routeCount + "路");
            holder.tvRouteBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tvRouteBadge.setVisibility(View.GONE);
        }

        int accentColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.accent);
        boolean isFocused = position == focusedPosition;

        if (isFocused) {
            holder.itemView.setBackgroundResource(R.drawable.bg_channel_focused);
            holder.tvNum.setTextColor(accentColor);
            holder.tvName.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(), android.R.color.white));
        } else {
            holder.itemView.setBackgroundResource(0);
            boolean isSelected = position == selectedPosition;
            int color = isSelected
                    ? accentColor
                    : ContextCompat.getColor(holder.itemView.getContext(),
                            android.R.color.white);
            holder.tvNum.setTextColor(color);
            holder.tvName.setTextColor(color);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChannelClick(channel, holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return mDiffer.getCurrentList().size();
    }

    public void setChannels(List<ChannelInfo> channels) {
        setChannels(channels, null);
    }

    public void setChannels(List<ChannelInfo> channels, Runnable onCommitted) {
        mDiffer.submitList(
                channels != null ? channels : java.util.Collections.emptyList(),
                onCommitted);
    }

    public ChannelInfo getItem(int position) {
        if (position >= 0 && position < mDiffer.getCurrentList().size()) {
            return mDiffer.getCurrentList().get(position);
        }
        return null;
    }

    public int findChannelIndex(ChannelInfo channel) {
        return mDiffer.getCurrentList().indexOf(channel);
    }

    public void setSelectedPosition(int position) {
        int prev = this.selectedPosition;
        this.selectedPosition = position;
        if (prev >= 0 && prev < getItemCount()) notifyItemChanged(prev);
        if (this.selectedPosition >= 0 && this.selectedPosition < getItemCount())
            notifyItemChanged(this.selectedPosition);
    }

    public void setFocusedPosition(int position) {
        int prev = this.focusedPosition;
        this.focusedPosition = position;
        if (prev >= 0 && prev < getItemCount()) notifyItemChanged(prev);
        if (this.focusedPosition >= 0 && this.focusedPosition < getItemCount())
            notifyItemChanged(this.focusedPosition);
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvNum;
        TextView tvName;
        TextView tvRouteBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNum = itemView.findViewById(R.id.tvChannelNum);
            tvName = itemView.findViewById(R.id.tvChannelName);
            tvRouteBadge = itemView.findViewById(R.id.tvRouteBadge);
        }
    }
}