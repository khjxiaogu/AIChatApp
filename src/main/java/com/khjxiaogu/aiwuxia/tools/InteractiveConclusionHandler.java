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
package com.khjxiaogu.aiwuxia.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class InteractiveConclusionHandler {

	public static void main(String[] args) throws IOException {
		File workingPath=FileUtil.choose();
		if(workingPath==null)return;
		LLMConnector.initDefault();
		String system=FileUtil.readString(new File("save","conclusionprompt.txt"));
		File output=new File("contentOutput.txt");
		try(PrintStream fos=new PrintStream(output,"UTF-8")){
			for(File f:workingPath.listFiles()) {
				String in=new String(FileUtil.readAll(f),"UTF-16LE");
				Builder ar=AIRequest.builder("admin").enableDeepThink().taskType(TaskType.ANALYSIS).strength(ReasoningStrength.STRONG).streamed().temperature(1f).maxTokens(8192);
				ar.addHistoryItem(Role.SYSTEM,system);
				ar.addHistoryItem(Role.USER,in);
				AIOutput ao=LLMConnector.call(ar.build());
				FileUtil.printAndCollectContent(ao.getReasoner());
				System.out.println();
				System.out.println("==========begin content==========");
				String last=FileUtil.printAndCollectContent(ao.getContent());
				fos.println();
				fos.println("=========="+f.getName()+"==========");
				fos.println(last);
			}
			
		}
	}

}
