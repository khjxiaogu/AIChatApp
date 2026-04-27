package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;

public interface ISaveData {

	ExtraData getData();

	HistoryHolder getHistory();

}