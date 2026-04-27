package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;

public class SavedData implements ISaveData {
	ExtraData data;
	HistoryHolder history;
	public ExtraData getData() {
		return data;
	}
	public HistoryHolder getHistory() {
		return history;
	}
	public SavedData(ExtraData data, HistoryHolder history) {
		super();
		this.data = data;
		this.history = history;
	}
	public SavedData() {
		this.history = new MemoryHistory();
		this.data = new AISession.ExtraData();
	}
}
