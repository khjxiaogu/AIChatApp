package com.khjxiaogu.aiwuxia.utils;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface BotCallback{
	void call(int status,JsonObject data);
}