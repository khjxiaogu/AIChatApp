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
package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;
import com.khjxiaogu.webserver.loging.LogStream;

public class AIServerMain {

	public AIServerMain() {
		// TODO Auto-generated constructor stub
	}
	public final static SimpleDateFormat logformatter = new SimpleDateFormat("yyyy-MM-dd-hh-mm-ss'.log'");
	public static void main(String[] args) throws ClassNotFoundException, InterruptedException, SQLException, IOException {
		File log = new File( "latest.log");
		new File("logs/").mkdir();
		if (!log.exists())
			log.createNewFile();
		else {
			log.renameTo(new File(new File( "logs/"), logformatter.format(new Date())));
			log = new File("latest.log");
			log.createNewFile();
		}
		try (FileOutputStream fos = new FileOutputStream(log);
		        LogStream ls = new LogStream(fos)) {
			System.setOut(ls);
			System.setErr(ls);
			BasicWebServerBuilder.build().createURIRoot()
			.createWrapper(new AIChatService(new File("save"))).rule("/aichat")
			.complete()
			.complete()
			.setNotFound(new File(new File("save"), "404.html"))
			.compile()
			.serverHttp(8998)

			.info("http服务端 http://0.0.0.0:8998/aichat 已开启")
			.info("服务端已开启")
			.info("网页服务端By khjxiaogu 启动完毕").await();
		}
	}

}
