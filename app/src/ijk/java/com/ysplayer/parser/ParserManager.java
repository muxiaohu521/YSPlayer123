package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.util.ArrayList;
import java.util.List;

public final class ParserManager {

    private static final String TAG = "ParserManager";
    private static final ParserManager INSTANCE = new ParserManager();

    private final List<ChannelParser> parsers = new ArrayList<>();

    private ParserManager() {
        registerParsers();
    }

    public static ParserManager getInstance() {
        return INSTANCE;
    }

    private void registerParsers() {
        parsers.add(new YangshipinParser());
        parsers.add(new CctvParser());
        parsers.add(new IqiluParser());
        parsers.add(new JstvParser());
        parsers.add(new QtvParser());
        parsers.add(new CztvParser());
        parsers.add(new HebrtvParser());
        parsers.add(new DbwParser());
        parsers.add(new GxtvParser());
        parsers.add(new GzstvParser());
        parsers.add(new FjtvParser());
        parsers.add(new SnrtvParser());
        parsers.add(new MgtvParser());
        parsers.add(new KankanewsParser());
        parsers.add(new NmtvParser());
        parsers.add(new SxrtvParser());
        parsers.add(new LotustvParser());
        parsers.add(new HnntvParser());
        parsers.add(new JxntvParser());

        LogUtil.i(TAG + ": registered " + parsers.size() + " parsers");

        YangshipinParser.warmUp();
    }

    public boolean canParse(String url) {
        return findParser(url) != null;
    }

    public ChannelParser findParser(String url) {
        if (url == null || url.isEmpty()) return null;
        for (ChannelParser parser : parsers) {
            if (parser.canParse(url)) {
                return parser;
            }
        }
        return null;
    }

    public void parse(String url, String userAgent, ChannelParser.ParseCallback callback) {
        ChannelParser parser = findParser(url);
        if (parser == null) {
            LogUtil.w(TAG + ": no parser for: " + url);
            if (callback != null) {
                callback.onError("没有找到适用于该网址的解析器: " + url);
            }
            return;
        }
        LogUtil.i(TAG + ": using parser [" + parser.getName() + "] for: " + url);
        parser.parse(url, userAgent, callback);
    }

    public List<ChannelParser> getParsers() {
        return new ArrayList<>(parsers);
    }
}