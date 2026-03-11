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
