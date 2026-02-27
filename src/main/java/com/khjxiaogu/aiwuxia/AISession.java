package com.khjxiaogu.aiwuxia;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.state.GameStage;
import com.khjxiaogu.aiwuxia.state.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.HistoryItem;
import com.khjxiaogu.aiwuxia.state.MessageItem;
import com.khjxiaogu.aiwuxia.state.StateIntf;

public class AISession implements Serializable, Cloneable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 7636370149170291121L;
	/**
	 * 
	 */
	public static class AIData{
		private StateIntf state=new StateIntf();
		protected Map<String,String> extraData=new HashMap<>(); 
		private int row;
		private int minRow=0;
		private GameStage stage = GameStage.INITIALIZE;
		private Usage usage = new Usage();
	}
	protected HistoryHolder history;
	protected AIData data;
	

	transient boolean isGenerating;
	public AIData getData() {
		return data;
	}
	public AISession(HistoryHolder history, AIData data) {
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
			if(role!=hi.getRole()||(!hi.shouldSend&&isSendable)) {
				hi=null;
			}
		}
		if(hi==null) {
			hi=new HistoryItem(history.newUniqueId(),role,content+"\n",isSendable);
			history.add(hi);
			postMessage(hi.getIdentifier(),hi.getRole(),hi.getContent().toString());
		}else {
			hi.appendLine(content, isSendable);
			appendMessage(hi.getIdentifier(),content+"\n");
		}
	}
	public void appendCh(Role role,String ch,boolean isSendable) {
		HistoryItem hi=null;
		if(!history.isEmpty()) {
			hi=getLast();
			if(role!=hi.getRole()||(!hi.shouldSend&&isSendable)) {
				hi=null;
			}
		}
		if(hi==null) {
			hi=new HistoryItem(history.newUniqueId(),role,ch,isSendable);
			history.add(hi);
			postMessage(hi.getIdentifier(),hi.getRole(),hi.getContent().toString());
		}else {
			hi.append(ch, isSendable);
			appendMessage(hi.getIdentifier(),ch);
		}
	}
	public void appendInvisibleLine(Role role,String content) {
		HistoryItem hi=null;
		if(!history.isEmpty()) {
			hi=getLast();
			if(role!=hi.getRole()||(!hi.shouldSend)) {
				hi=null;
			}
		}
		if(hi==null) {
			hi=new HistoryItem(history.newUniqueId(),role,"",content+"\n");
			history.add(hi);
			postMessage(hi.getIdentifier(),hi.getRole(),hi.getContent().toString());
		}else {
			hi.appendSending(content+"\n");
		}
	}
	public void add(Role role,String content,boolean isSendable) {
		HistoryItem hi=new HistoryItem(history.newUniqueId(),role,content,isSendable);
		history.add(hi);
		postMessage(hi.getIdentifier(),hi.getRole(),hi.getContent().toString());
	}
	public void add(Role role,String content,String sendContent) {
		HistoryItem hi=new HistoryItem(history.newUniqueId(),role,content,sendContent);
		history.add(hi);
		postMessage(hi.getIdentifier(),hi.getRole(),hi.getContent().toString());
	}
	public void removeOf(int num) {
		history.removeOf(num);
		delMessage(num);
	}
	public HistoryItem removeLast() {
		HistoryItem removed= history.remove(history.size()-1);
		delMessage(removed.getIdentifier());
		return removed;
	}
	public HistoryItem getLast() {
		return history.get(history.size()-1);
	}
	public void postMessage(int id,Role role,String message) {
		setUpdated();
	}
	public void appendMessage(int id,String title) {
		setUpdated();
	}
	public void delMessage(int id) {
		setUpdated();
	}
	boolean isUpdated;
	public boolean checkAndUnsetUpdated() {
		boolean res=isUpdated;
		isUpdated=false;
		return res;
	}
	public void setUpdated() {
		isUpdated=true;
		
	}
	public void onGenComplete() {
		setUpdated();
		isGenerating=false;
	}
	public void setScene(String type,String value) {
		
	}
	public void addUsage(Usage usage) {
		data.usage.add(usage);
	}
	public String getUsage() {
		return data.usage.toString();
	}
	public String getPrice() {
		return data.usage.calculatePrice();
	}
	public GameStage getStage() {
		return data.stage;
	}
	public Map<String, String> getExtra() {
		return data.extraData;
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
	public int getMinRow() {
		return data.minRow;
	}
	public void setMinRow(int minRow) {
		data.minRow=minRow;
	}
	public int getRow() {
		return data.row;
	}
	public StateIntf getState() {
		return data.state;
	}
	public void onGenStart() {
		isGenerating=true;
	}
	public void postMessages(List<MessageItem> items) {
		setUpdated();
	}
	public void minRows(int size) {
		data.row-=size;
	}
}