package com.ysplayer.bean;

import java.util.ArrayList;
import java.util.List;

public class SettingGroup {
    private int groupIndex;
    private String groupName;
    private List<SettingItemInfo> items;

    public SettingGroup(int groupIndex, String groupName) {
        this.groupIndex = groupIndex;
        this.groupName = groupName;
        this.items = new ArrayList<>();
    }

    public int getGroupIndex() {
        return groupIndex;
    }

    public String getGroupName() {
        return groupName;
    }

    public List<SettingItemInfo> getItems() {
        return items;
    }

    public void setItems(List<SettingItemInfo> items) {
        this.items = items;
    }
}