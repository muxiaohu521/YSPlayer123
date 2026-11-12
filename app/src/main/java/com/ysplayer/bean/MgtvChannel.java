package com.ysplayer.bean;

import java.util.Objects;

public class MgtvChannel {

    private String name;
    private String cameraId;

    public MgtvChannel() {
    }

    public MgtvChannel(String name, String cameraId) {
        this.name = name;
        this.cameraId = cameraId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCameraId() {
        return cameraId;
    }

    public void setCameraId(String cameraId) {
        this.cameraId = cameraId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MgtvChannel)) return false;
        MgtvChannel that = (MgtvChannel) o;
        return Objects.equals(name, that.name) && Objects.equals(cameraId, that.cameraId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, cameraId);
    }
}