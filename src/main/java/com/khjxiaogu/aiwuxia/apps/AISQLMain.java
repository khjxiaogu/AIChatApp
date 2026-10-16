/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class AISQLMain extends AIApplication {

	String system;
	public AISQLMain(File folder){
		try {
			system = FileUtil.readString(new File(folder, "prompt.txt")).replace("\r", "");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		handlers.add((state, ret) -> {

			String text=ret.toText();
				if (text.startsWith("定义")) {
					state.getState().extras.add(text.substring(2).trim());

					return null;
				}
			
			return ret;
		});
		// check interface
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.STARTED) {
				if ("查看定义".equals(ret.toText())) {
					try (HistoryWriteContext write = state.beginWrite(Role.USER)) {
						write.appendDisplayOnly(ret);
					}
					try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
						write.appendDisplayOnly(constructSystem(state.getState()));
					}
					return null;
				}
			}
			return ret;
		});
		// AI response, always valid
		handlers.add((state, ret) -> {
			try (HistoryWriteContext write = state.beginWrite(Role.USER)) {
				write.append(ret, true);
			}
			try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
				sendAndProcessResultStreamed(state, write, constructAIrequest(state, constructSystem(state.getState())));
			}
			state.addDialogRow();

			return null;
		});
	}

	public ApplicationState sendAndProcessResultStreamed(AISession state, HistoryWriteContext write, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
		ApplicationState oldstate = precessResponse(resp, state, write);
		write.setLastState(oldstate);
		return oldstate;
	}

	public class MessageAndRole {
		String role;
		CharSequence message;

		public MessageAndRole(String role, CharSequence charSequence) {
			super();
			this.role = role;
			this.message = charSequence;
		}

	}

	public AIRequest constructAIrequest(AISession state, String status) {

		Builder builder=AIRequest.builder(state).taskType(TaskType.CODE).strength(ReasoningStrength.WEAK).streamed();
		builder.addHistoryItem(Role.SYSTEM,system);
		if (status != null && !status.isEmpty())
			builder.addHistoryItem(Role.SYSTEM,status);
		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				builder.addHistoryItem(hi);
			}
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return builder.temperature(1.7f).maxTokens(8192).build();

	}

	public String constructBackLog(AISession state) {
		StringBuilder sb = new StringBuilder("");
		for (HistoryItem hs : state.getHistory()) {
			sb.append(getRoleName(state,hs.getRole())).append("：").append(hs.getDisplayContent())
				.append("\n");
		}

		return sb.toString();

	}

	public void provideInitial(AISession state) {
		if(state.getStage()!=ApplicationStage.STARTED) {
			try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
				write.appendDisplayOnly("请提供表定义，定义必须开始以“定义”二字");
			}
			state.setStage(ApplicationStage.STARTED);
		}
	}

	public ApplicationState precessResponse(AIOutput op, AISession state, HistoryWriteContext write) throws IOException {
		boolean isWaiting = true;
		int status = 0;
		
		try(BufferedReader scan=new BufferedReader(op.getContent())){
			ApplicationState oldstate = new ApplicationState(state.getState());
			// 思维链改为后台异步读流写入本轮记录，正文可以立刻开始处理
			CompletableFuture<Void> reasoner=handleReasonerContent(op,state,write);
			boolean nstateModified = false;
			while (true) {
				String last = scan.readLine();
				if(last==null)
					break;
				if (isWaiting && last.isEmpty()) {
					continue;
				}
	
				if (isWaiting) {
					//System.out.println("\n=================Content===============");
					isWaiting = false;
				}
				//System.out.println(last);
				if (status == 0) {
					if (last.startsWith("==SQL==")) {
						status = 2;
						write.appendContext(last + "\n");
					} else {  
						write.appendLine(last, true);
					}
				}  else {
					write.appendLine(last, true);
				}
	
			}
			write.appendLine("\n输入“重新生成”重新生成，输入“撤回”删除上一次对话。", false);
			// 等待后台思维链读完，读流失败在这里按原来的方式抛出
			reasoner.join();
	
			return nstateModified ? oldstate : null;
		}
	}

	@Override
	public String getMemory(AISession state) {
		return null;
	}
	public String constructSystem(ApplicationState state) {
		if (state == null || state.extras.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("=====定义=====\n");
		for (String s:state.extras)
			sb.append(s).append("\n");
		return sb.toString();

	}

	@Override
	public String getName() {
		return "sql";
	}

	@Override
	public String getBrief(AISession state) {
		if(state.getHistory().size()<2)return null;
		CharSequence content="SQL";
		return content.subSequence(1, Math.min(10, content.length())).toString();
	}
}
