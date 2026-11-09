package com.ysplayer;

import android.content.Context;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class WrapAroundLayoutManager extends LinearLayoutManager {

    private boolean mScrollToPositionInProgress;

    public WrapAroundLayoutManager(Context context) {
        super(context);
    }

    @Override
    public void scrollToPosition(int position) {
        mScrollToPositionInProgress = true;
        try {
            super.scrollToPosition(position);
        } finally {
            mScrollToPositionInProgress = false;
        }
    }

    @Override
    public void scrollToPositionWithOffset(int position, int offset) {
        mScrollToPositionInProgress = true;
        try {
            super.scrollToPositionWithOffset(position, offset);
        } finally {
            mScrollToPositionInProgress = false;
        }
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
                                   RecyclerView.State state) {
        int itemCount = getItemCount();
        if (itemCount == 0) return 0;

        int scrolled = super.scrollVerticallyBy(dy, recycler, state);

        if (mScrollToPositionInProgress) {
            return scrolled;
        }

        if (Math.abs(scrolled) < Math.abs(dy)) {
            if (dy > 0) {
                int lastVisible = findLastVisibleItemPosition();
                if (lastVisible >= itemCount - 1) {
                    scrollToPositionWithOffset(0, 0);
                }
            } else if (dy < 0) {
                int firstVisible = findFirstVisibleItemPosition();
                if (firstVisible <= 0) {
                    scrollToPositionWithOffset(itemCount - 1, 0);
                }
            }
        }

        return scrolled;
    }
}