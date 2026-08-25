package com.khjxiaogu.aiwuxia.state.session;

import java.io.Closeable;

public interface ChatIdentity extends Closeable {
	String getChatId();
}
