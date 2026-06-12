package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;

public class HistoryCompactor {
	public static interface DoHistoryCompact{
		void compact(String str) throws IOException;
	}
	public static void compact(HistoryHolder history,int limit,int min,
		Function<Role,String> roleNameTag,
		DoHistoryCompact compactor) throws IOException {
		int len=0; 
		int reasoning=0;
		Iterator<HistoryItem> it=history.validContextIterator();
		while(it.hasNext()) {
			HistoryItem hi=it.next();
			long tokenLen=hi.getTokenLength();
			if(tokenLen==0) {
				history.setTokenLength(hi,tokenLen=TokenSimulatedCounter.fastCountLength(hi.getContextContent()));
			}
			len+=tokenLen;
			if(hi.maySendReasoner())
				reasoning++;
		}
		if(reasoning>30) {
			it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				if(hi.maySendReasoner()) {
					history.setSendReasoner(hi, false);
					reasoning--;
					if(reasoning<=10)
						break;
				}
			}
		}
		if(len>=limit) {//more than 100000 text:about 60k context,remove until 20000
			StringBuilder summery=new StringBuilder();
			List<HistoryItem> his=new ArrayList<>();
			it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				len-=hi.getTokenLength();
				if(hi.getRole()!=Role.SYSTEM) {
					summery.append(roleNameTag.apply(hi.getRole()));
					summery.append(hi.getDisplayContent()).append("\n");
				}
				his.add(hi);
				
				if(hi.getRole()==Role.ASSISTANT) {
					if(len<=min) {
						break;
					}
				}
			}
			compactor.compact(summery.toString());
			his.forEach(t->history.setValidContext(t,false));
			
		}
	}
}
