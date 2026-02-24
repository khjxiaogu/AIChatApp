package com.khjxiaogu.aiwuxia;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.scheme.Usage;

public class AIState implements Serializable, Cloneable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 7636370149170291121L;
	/**
	 * 
	 */
	public static class AIData{
		private static final long serialVersionUID = 629802023373098821L;
		private StateIntf state=new StateIntf();
		
		private int row;
		private GameStage stage = GameStage.INITIALIZE;
		private Usage usage = new Usage();
	}
	protected HistoryHolder history;
	protected AIData data;
	protected Map<String,String> extraData=new HashMap<>(); 
	public AIData getData() {
		return data;
	}
	public AIState(HistoryHolder history, AIData data) {
		super();
		this.history = history;
		this.data = data;
	}
	@Override
	protected Object clone() throws CloneNotSupportedException {
		return super.clone();
	}
	public HistoryHolder getHistory() {
		return history;
	}
	public void appendReasoning(String content) {
		getLast().createReasonContent().append(content);
	}
	public void appendLine(Role role,String content,boolean isSendable) {
		HistoryItem hi=null;
		if(!history.isEmpty()) {
			hi=getLast();
			if(role!=hi.role||(!hi.shouldSend&&isSendable)) {
				hi=null;
			}
		}
		if(hi==null) {
			hi=new HistoryItem(history.size(),role,content+"\n",isSendable);
			history.add(hi);
			postMessage(hi.identifier,hi.role.getName(),hi.getContent().toString());
		}else {
			hi.appendLine(content, isSendable);
			appendMessage(hi.identifier,content+"\n");
		}
	}
	public void appendInvisibleLine(Role role,String content) {
		HistoryItem hi=null;
		if(!history.isEmpty()) {
			hi=getLast();
			if(role!=hi.role||(!hi.shouldSend)) {
				hi=null;
			}
		}
		if(hi==null) {
			hi=new HistoryItem(history.size(),role,"",content+"\n");
			history.add(hi);
			postMessage(hi.identifier,hi.role.getName(),hi.getContent().toString());
		}else {
			hi.appendSending(content+"\n");
		}
	}
	public void add(Role role,String content,boolean isSendable) {
		HistoryItem hi=new HistoryItem(history.size(),role,content,isSendable);
		history.add(hi);
		postMessage(hi.identifier,hi.role.getName(),hi.getContent().toString());
	}
	public void add(Role role,String content,String sendContent) {
		HistoryItem hi=new HistoryItem(history.size(),role,content,sendContent);
		history.add(hi);
		postMessage(hi.identifier,hi.role.getName(),hi.getContent().toString());
	}
	public void removeOf(int num) {
		history.removeOf(num);
		delMessage(num);
	}
	public HistoryItem removeLast() {
		HistoryItem removed= history.remove(history.size()-1);
		delMessage(removed.identifier);
		return removed;
	}
	public HistoryItem getLast() {
		return history.get(history.size()-1);
	}
	public void postMessage(int id,String title,String message) {
		
	}
	public void appendMessage(int id,String title) {
		
	}
	public void delMessage(int id) {
		
	}
	public void onGenComplete() {}
	public void addUsage(Usage usage) {
		data.usage.add(usage);
	}
	public String getUsage() {
		return data.usage.toString();
	}
	public GameStage getStage() {
		return data.stage;
	}
	public void setStage(GameStage stage) {
		data.stage = stage;
	}
	public void addRow() {
		data.row++;
	}
	public void minRow() {
		data.row--;
	}
	public StateIntf getState() {
		return data.state;
	}
}