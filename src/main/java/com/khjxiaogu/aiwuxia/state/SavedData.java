package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;

public class SavedData implements ISaveData {
	@FunctionalInterface
	public static interface DoSaveData{
		void save(ISaveData data);
	}
	ExtraData data;
	HistoryHolder history;
	DoSaveData doFlush;
	public ExtraData getExtraData() {
		return data;
	}
	public HistoryHolder getHistory() {
		return history;
	}
	public SavedData(ExtraData data, HistoryHolder history, DoSaveData doFlush) {
		super();
		this.data = data;
		this.history = history;
		this.doFlush=doFlush;
	}
	@Override
	public void flush() {
		history.flush();
		doFlush.save(this);
	}
}
