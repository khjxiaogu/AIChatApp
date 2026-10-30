package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

public class HistoryCompactor {
	public static interface DoHistoryCompact{
		void compact(String str) throws IOException;
	}

	/**
	 * 只取条目里的纯文本内容。
	 * <p>
	 * 图片、音频、视频、工具调用/工具结果等非文本段一律跳过：这些内容要么体积很大
	 * （例如图片的 base64 data URL），要么不适合写进摘要提示词。摘要只压对话文本，
	 * 避免摘要请求本身超出模型的上下文上限。
	 * </p>
	 *
	 * @param contents 条目内容，可为 null
	 * @return 纯文本拼接结果，不为 null
	 */
	private static String plainTextOnly(MessageContents contents) {
		if (contents == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (MessageContent content : contents) {
			if (content != null && content.isPlainText()) {
				sb.append(content.toText());
			}
		}
		return sb.toString();
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
				boolean shouldContainReasoner=false;
				if(hi.getReasoningContent()!=null&&!hi.getReasoningContent().isEmpty()) {
					for(MessageContent msgc:hi.getReasoningContent()) {
						if(msgc instanceof ToolContent) {
							shouldContainReasoner=true;
							break;
						}
					}
					if(shouldContainReasoner) {
						for(MessageContent msgc:hi.getReasoningContent()) {
							tokenLen+=(TokenSimulatedCounter.fastCountLength( msgc.toText()));
							
						}
					}
				}tokenLen+=TokenSimulatedCounter.fastCountLength(hi.getContextContent());
				history.setTokenLength(hi,tokenLen);
			}
			len+=tokenLen;
			if(hi.maySendReasoner()&&hi.getRole()==Role.ASSISTANT)
				reasoning++;
		}
		if(reasoning>30) {
			it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				if(hi.maySendReasoner()&&hi.getRole()==Role.ASSISTANT) {
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
					// 只用纯文本：图片等非文本内容不进入摘要（显示内容里可能带完整 base64）
					String text=plainTextOnly(hi.getDisplayContent());
					if(!text.trim().isEmpty()) {
						summery.append(roleNameTag.apply(hi.getRole()));
						summery.append(text).append("\n");
					}
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
