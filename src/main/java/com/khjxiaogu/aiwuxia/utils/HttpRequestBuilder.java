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
package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class HttpRequestBuilder {
	private StringBuilder url;
	List<String[]> headers=new ArrayList<>();
	boolean followRedirect=true;
	public HttpRequestBuilder(String ourl) {
		url=new StringBuilder(ourl);
	}
	public static HttpRequestBuilder create(String host) {
		return new HttpRequestBuilder("https://"+host).host(host);
	}
	public static HttpRequestBuilder create(String protocol,String host) {
		return new HttpRequestBuilder(protocol+"://"+host).host(host);
	}
	public HttpRequestBuilder url(String v) {
		url.append(v);
		return this;
	}
	public HttpRequestBuilder url(int v) {
		url.append(v);
		return this;
	}
	public HttpRequestBuilder url(char v) {
		url.append(v);
		return this;
	}
	public HttpRequestBuilder header(String k,String v) {
		headers.add(new String[] {k,v});
		return this;
	}
	public HttpRequestBuilder referer(String v) {
		headers.add(new String[] {"referer",v});
		return this;
	}
	public HttpRequestBuilder contenttype(String v) {
		headers.add(new String[] {"content-type",v});
		return this;
	}
	public HttpRequestBuilder cookie(String v) {
		headers.add(new String[] {"cookie",v});
		return this;
	}
	public HttpRequestBuilder host(String v) {
		headers.add(new String[] {"Host",v});
		return this;
	}
	public HttpRequestBuilder defUA() {
		headers.add(new String[] {"User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.149 Safari/537.36"});
		return this;
	}
	public HttpRequestBuilder ua(String v) {
		headers.add(new String[] {"User-Agent",v});
		return this;
	}
	public HttpRequestBuilder noRedirect() {
		this.followRedirect=false;
		return this;
	}
	public static class OpenedConnectionBuilder {
		HttpURLConnection huc;
		public OutputStream os;
		public OpenedConnectionBuilder(HttpURLConnection huc,boolean doOut) throws IOException {
			super();
			this.huc = huc;
			if(doOut)
			this.os = huc.getOutputStream();
		}
		public OpenedConnectionBuilder(HttpURLConnection huc) {
			super();
			this.huc = huc;
		}
		public OpenedConnectionBuilder send(String s) throws IOException {
			if(os==null)
				os=huc.getOutputStream();
			os.write(s.getBytes(StandardCharsets.UTF_8));
			
			return this;
		}
		public OpenedConnectionBuilder send(byte[] s) throws IOException {
			if(os==null)
				os=huc.getOutputStream();
			os.write(s);
			return this;
		}
		public String getRedirect() {
			String res= huc.getHeaderField("Location");
			return res;
		}
		private void closeOutput() {
			if(os!=null)
				try {
					OutputStream cos=os;
					os=null;
					cos.close();
				}catch(Throwable t) {
					t.printStackTrace();
				}
		}
		private void close() {
			closeOutput();
			huc.disconnect();
		}
		public String readString() throws IOException {
			closeOutput();
			try(InputStream is=huc.getInputStream()){
				return new String(FileUtil.readAll(is), StandardCharsets.UTF_8);
			}catch(IOException ex){
				generateExceptionFromError(ex);
			}finally{
				close();
			}
			return null;//this should never happen
		}
		public JsonObject readJson() throws IOException {
			closeOutput();
			try(InputStream is=huc.getInputStream()){
				JsonObject json= JsonParser.parseString(FileUtil.readString(is)).getAsJsonObject();
				return json;
			}catch(IOException ex){
				generateExceptionFromError(ex);
			}finally{
				close();
			}
			return null;//this should never happen
			
		}
		private void generateExceptionFromError(IOException ex) throws IOException {
			try(InputStream errorStream=huc.getErrorStream()){
				throw new IOException(FileUtil.readString(errorStream),ex);
			}
		}
		public void readSSE(SSEListener listener) throws IOException {
			closeOutput();
			try(InputStream is=huc.getInputStream();Scanner scan=new Scanner(is,StandardCharsets.UTF_8)){
				
				try{
					while(scan.hasNextLine()) {
						String resp=scan.nextLine();
						//System.out.println(resp);
						if(resp.isEmpty()||resp.startsWith(":"))
							continue;
						int idx=resp.indexOf(":");
						String dataEvent=resp.substring(0,idx);
						if(resp.charAt(idx+1)==' ') idx++;
						String dataElem=resp.substring(idx+1);
						if(!listener.accept(dataEvent, dataElem)) {
							break;
						}
					}
				}catch(Throwable err) {
					err.printStackTrace();
				}
			}catch(IOException ex){
				generateExceptionFromError(ex);
			}finally{
				close();
			}
			
		}
	}
	private HttpURLConnection openConn() throws IOException {
		URL url = new URL(this.url.toString());
		HttpURLConnection huc = (HttpURLConnection) url.openConnection();
		huc.setInstanceFollowRedirects(followRedirect);
		
		huc.setConnectTimeout(5000);
		huc.setReadTimeout(5000);
		for(String[] header:headers) {
			huc.setRequestProperty(header[0], header[1]);
		}
		
		return huc;
	}
	public OpenedConnectionBuilder post(boolean doOutput) throws IOException {
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("POST");
		huc.setDoOutput(doOutput);
		huc.setDoInput(true);
		huc.setReadTimeout(30*60*1000);
		huc.connect();
		return new OpenedConnectionBuilder(huc,doOutput);
		
	}
	public OpenedConnectionBuilder post() throws IOException {
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("POST");
		huc.setDoOutput(true);
		huc.setDoInput(true);
		huc.setReadTimeout(30*60*1000);
		//huc.connect();
		return new OpenedConnectionBuilder(huc,true);
	}
	public OpenedConnectionBuilder get(boolean doOutput) throws IOException {
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("GET");
		huc.setDoOutput(doOutput);
		huc.setDoInput(true);
		huc.setReadTimeout(30*60*1000);
		huc.connect();
		return new OpenedConnectionBuilder(huc,doOutput);
	}
	public OpenedConnectionBuilder get() throws IOException {
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("GET");
		huc.setReadTimeout(30*60*1000);
		huc.connect();
		return new OpenedConnectionBuilder(huc,false);
	}
	public OpenedConnectionBuilder open(String met,boolean doInput,boolean doOutput) throws IOException {
		HttpURLConnection huc=openConn();
		huc.setRequestMethod(met);
		huc.setDoOutput(doOutput);
		huc.setDoInput(doInput);
		huc.connect();
		return new OpenedConnectionBuilder(huc,doOutput);
	}
	

}
