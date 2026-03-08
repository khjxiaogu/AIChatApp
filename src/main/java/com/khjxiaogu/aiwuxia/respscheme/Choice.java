package com.khjxiaogu.aiwuxia.respscheme;

public class Choice{
	public static class Message{
		public String content;
		public String reasoning_content;
		public String role;
	}
	public String finish_reason;
	public int index;
	public Choice.Message message;
	public Choice.Message delta;
	
}
