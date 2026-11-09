package com.ysplayer.bean;

public class SettingItemInfo {
    private String name;
    private boolean selected;
    private int tagValue;

    public SettingItemInfo(String name, boolean selected, int tagValue) {
        this.name = name;
        this.selected = selected;
        this.tagValue = tagValue;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public int getTagValue() {
        return tagValue;
    }

    public void setTagValue(int tagValue) {
        this.tagValue = tagValue;
    }
}