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
package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.AIChatWindow;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalRequest;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallApprover;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;

import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.advanced.AdvancedPlayer;

public class AppAISession extends AISession {

	File saveData;
	AIChatWindow window;

	/**
	 * 桌面端的工具调用许可管理器：与 WebSocket 会话同样是“同一会话串行询问 + 超时按拒绝”，
	 * 只是询问方式改为直接弹窗，答复在弹窗返回前回填，等待方随即被唤醒。
	 */
	private final ToolApprovalManager toolApprovalManager = new ToolApprovalManager(this::requestToolCallApproval);

	public AppAISession(String user, ISaveData data,AIApplication aiapp,File saveData,AIChatWindow win) {
		super(user, data,aiapp);
		this.saveData=saveData;
		this.window=win;
		// 父类构造期本类字段尚未初始化，因此在这里（字段就绪后）再把许可方写入工具调用管理器：
		// 权限监听器判为“询问”的工具调用会弹窗征求用户许可
		getToolCallManager().setApprover(getUserApprovalApprover());
	}

	@Override
	public void refillChatBox(MessageContents text) {
		window.setInput(text.toText());
	}

	@Override
	public void requestUserInput(String input, String prompt, Consumer<String> consumer) {
		consumer.accept(window.prompt(prompt));
		
	}

	/**
	 * 弹窗询问用户是否允许执行该工具。
	 * <p>
	 * 由 {@link #toolApprovalManager} 在自己的询问线程上回调，弹窗阻塞该线程直到用户选择；
	 * 生成线程此时正阻塞等待许可，因此答复必须在本方法内直接回填，
	 * <b>不能</b>提交到 {@code commandExec} 上排队（会排在同会话的生成任务之后而死锁）。
	 * </p>
	 *
	 * @param request 许可请求记录
	 */
	protected void requestToolCallApproval(ToolApprovalRequest request) {
		// 优先展示工具声明的人可读参数，未声明时回退到原始 JSON
		String arguments = request.getDisplayArguments();
		if (arguments == null || arguments.trim().isEmpty()) {
			arguments = request.getArguments();
		}
		boolean approved = window.confirm("模型请求执行工具（" + request.getToolName() + "），是否允许？\n"
				+ (request.getReason() == null ? "" : "原因：" + request.getReason() + "\n")
				+ "参数：\n" + arguments);
		if (approved) {
			request.approve();
			return;
		}
		// 拒绝时允许填写原因，原因会作为工具结果回传给模型；留空则使用默认未许可提示
		String denyReason = window.prompt("已拒绝执行工具「" + request.getToolName()
				+ "」。\n可填写拒绝原因（会转告模型，留空使用默认提示）：");
		request.deny(denyReason == null || denyReason.trim().isEmpty() ? null : denyReason.trim());
	}

	/**
	 * 获取本会话的工具调用许可管理器（同一会话串行询问、超时按拒绝处理）。
	 *
	 * @return 许可管理器
	 */
	public ToolApprovalManager getToolApprovalManager() {
		return toolApprovalManager;
	}

	/**
	 * 阻塞等待用户弹窗答复的许可方，已由构造函数写入工具调用管理器。
	 *
	 * @return 许可方
	 */
	public ToolCallApprover getUserApprovalApprover() {
		return toolApprovalManager.approverFor(user);
	}

	/**
	 * 放弃本会话全部待答复的许可请求（例如窗口关闭），一律按拒绝处理。
	 */
	public void cancelToolApprovals() {
		toolApprovalManager.cancelAll(user);
	}

	@Override
	public void close() {
		// 释放可能正在等待用户许可的生成线程
		cancelToolApprovals();
		super.close();
	}

	@Override
	public void sendNotice(String msg) {
		window.showMessage(msg);
	}

	@Override
	public void postAudioComplete(int id, String audioId) {
		super.postAudioComplete(id, audioId);

		 try {
        // 创建一个文件输入流指向你的MP3文件
        FileInputStream fileInputStream = new FileInputStream("save/voice/"+audioId+".mp3");
        // 创建AdvancedPlayer对象
        
			AdvancedPlayer player = new AdvancedPlayer(fileInputStream);
			player.play();
		} catch (JavaLayerException | FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}


}
