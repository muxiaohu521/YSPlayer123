package com.ysplayer.bean;

import java.util.ArrayList;
import java.util.List;

public class ChannelInfo {

    private String name;
    private int channelNum;
    private String webUrl;
    private List<Route> routes = new ArrayList<>();
    private int currentRouteIndex;

    public ChannelInfo() {
        this.currentRouteIndex = 0;
    }

    public ChannelInfo(String name, String webUrl) {
        this();
        this.name = name;
        this.webUrl = webUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getChannelNum() {
        return channelNum;
    }

    public void setChannelNum(int channelNum) {
        this.channelNum = channelNum;
    }

    public String getWebUrl() {
        if (routes != null && !routes.isEmpty()
                && currentRouteIndex >= 0 && currentRouteIndex < routes.size()) {
            return routes.get(currentRouteIndex).getUrl();
        }
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public int getCurrentRouteIndex() {
        return currentRouteIndex;
    }

    public void setCurrentRouteIndex(int index) {
        this.currentRouteIndex = index;
    }

    public String getCurrentRouteName() {
        if (routes != null && !routes.isEmpty()
                && currentRouteIndex >= 0 && currentRouteIndex < routes.size()) {
            return routes.get(currentRouteIndex).getName();
        }
        return "";
    }

    public int getRouteCount() {
        return routes != null ? routes.size() : 0;
    }

    public static class Route {
        private String name;
        private String url;

        public Route() {
        }

        public Route(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}