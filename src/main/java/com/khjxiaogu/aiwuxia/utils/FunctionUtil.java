package com.khjxiaogu.aiwuxia.utils;

import java.util.function.Supplier;

public class FunctionUtil {
	public static <T> T make(Supplier<T> supp) {
		return supp.get();
	}
}
