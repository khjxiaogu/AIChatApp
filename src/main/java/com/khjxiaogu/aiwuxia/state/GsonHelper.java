package com.khjxiaogu.aiwuxia.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;

public class GsonHelper {
    /** 默认的Gson实例，用于JSON序列化/反序列化（紧凑格式） */
    protected static Gson gs = new GsonBuilder()
    	.registerTypeAdapter(UsageTracker.class, new UsageTracker.Serilizer())
    	.registerTypeAdapter(MessageContents.class, new MessageContents.Serilizer<>())
    	.registerTypeAdapter(MutableMessageContents.class, new MessageContents.Serilizer<>())
    	.disableHtmlEscaping()
    	.create();
    /** 格式化的Gson实例，用于生成美观的JSON输出（带缩进） */
    protected static Gson ppgs = new GsonBuilder()
    	.registerTypeAdapter(UsageTracker.class, new UsageTracker.Serilizer())
    	.registerTypeAdapter(MessageContents.class, new MessageContents.Serilizer<>())
    	.registerTypeAdapter(MutableMessageContents.class, new MessageContents.Serilizer<>())
    	.disableHtmlEscaping()
    	.setPrettyPrinting().create();
    public static Gson getStorageGson() {
    	return gs;
    }
    public static Gson getPrettyPrintGson() {
    	return ppgs;
    }
}
