package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;

public interface SSEListener {
	boolean accept(String dataEvent,String dataElem) throws IOException;
}
