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

import java.util.ArrayDeque;
import java.util.Date;

import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;

public class AIGroupSession extends AISession {
	ArrayDeque<String> messageQueue=new ArrayDeque<>();
	public AIGroupSession(String user, HistoryHolder historym, ExtraData data, AIApplication aiapp) {
		super(user, historym, data, aiapp);
	}
	public void addMessage(String msg) {
		messageQueue.add(msg);
		if(messageQueue.size()>10)
			messageQueue.pollFirst();
	}
	public String getPrompt() {
		StringBuilder sb=new StringBuilder();
		sb.append("当前时间：").append(new Date().toLocaleString()).append("\n");
		while(true) {
			String msg=messageQueue.pollFirst();
			if(msg==null)break;
			sb.append(msg).append("\n");
		}
		return sb.toString();
	}
}
