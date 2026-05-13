package com.khjxiaogu.aiwuxia.llm.scheme;

import java.util.ArrayList;
import java.util.List;

import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolFunction;

public class ToolCallCollector {
	private static class ToolCallBuilder{
		String id;
		String name;
		StringBuilder argument=new StringBuilder();
	}
	public boolean isValid() {
		return !calls.isEmpty();
	}
	List<ToolCallBuilder> calls=new ArrayList<>(20);
	public void collect(ToolCall toolcall) {
		ToolCallBuilder current;
		if(toolcall.index==calls.size()) {
			current=new ToolCallBuilder();
			calls.add(current);
		}else {
			current=calls.get(toolcall.index);
		}
		if(toolcall.id!=null)
			current.id=toolcall.id;
		if(toolcall.function!=null) {
			if(toolcall.function.name!=null)
				current.name=toolcall.function.name;
			if(toolcall.function.arguments!=null)
				current.argument.append(toolcall.function.arguments);
		}
	}
	public List<ToolCall> build(){
		List<ToolCall> results=new ArrayList<>(calls.size());
		int index=0;
		for(ToolCallBuilder tcb:calls) {
			results.add(new ToolCall(index++,tcb.id,new ToolFunction(tcb.name,tcb.argument.toString())));
		}
		return results;
	}
}
