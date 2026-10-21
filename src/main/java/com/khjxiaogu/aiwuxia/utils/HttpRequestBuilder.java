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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class HttpRequestBuilder {
	private StringBuilder url;
	List<String[]> headers=new ArrayList<>();
	Proxy px;
	boolean followRedirect=true;
	/** 读超时（毫秒），在建立连接前生效（连接建立后 HttpURLConnection 不再应用新的读超时） */
	private int readTimeoutMs=30000;
	/** 调用方是否显式指定过读超时；显式指定后 post()/get() 不再套用默认的长超时 */
	private boolean readTimeoutSet;
	public HttpRequestBuilder(String ourl) {
		url=new StringBuilder(ourl);
		headers.add(new String[] {"Accept","*/*"});
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
	public HttpRequestBuilder proxy(Proxy px) {
		this.px=px;
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
		public byte[] readBytes() throws IOException {
			closeOutput();
			try(InputStream is=huc.getInputStream()){
				return FileUtil.readAll(is);
			}catch(IOException ex){
				generateExceptionFromError(ex);
			}finally{
				close();
			}
			return null;//this should never happen
		}
		public JsonObject readJson() throws IOException {
			closeOutput();
			if(huc.getResponseCode()>=300) {
				throw new IOException(huc.getResponseMessage());
			}
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
			InputStream errorStream = huc.getErrorStream();
			// 读超时、连接被重置等情况下对端没有任何响应体，getErrorStream() 返回 null。
			// 这里必须直接把原始异常抛出去：此前的实现会把 null 交给 FileUtil.readString，
			// 抛出 NPE 并把真正的失败原因和整个异常都丢掉。
			if (errorStream == null)
				throw ex;
			try (InputStream es = errorStream) {
				String body = FileUtil.readString(es);
				throw new IOException(body.isEmpty() ? ex.getMessage() : body, ex);
			}
		}

		/**
		 * 读取一条 SSE 流，把每个 {@code data} 字段交给监听器。
		 * <p>
		 * 与旧实现的区别（都是为了“失败必须能被调用方看到”）：
		 * </p>
		 * <ul>
		 *   <li>改用 {@link BufferedReader} 逐行读取。旧的 {@code Scanner} 会把自己的
		 *       {@link IOException}（包括 socket 读超时）吞掉并让 {@code hasNextLine()} 返回 false，
		 *       调用方因此把“断流/超时”误当成“正常结束”，下游的流永远不会被关闭。</li>
		 *   <li>监听器抛出的异常不再被吞掉，转成 {@link IOException} 抛给调用方。</li>
		 *   <li>响应体不是 SSE（例如网关错误页、纯 JSON 错误体）时直接报错，
		 *       不再把无法解析的行当成正常结束。</li>
		 *   <li>只把 {@code data} 字段交给业务，{@code event}/{@code id}/{@code retry}
		 *       等字段按 SSE 规范忽略。</li>
		 * </ul>
		 *
		 * @param listener 事件处理者，返回 false 表示提前结束读取
		 * @throws IOException 读流失败、响应不是 SSE，或监听器处理失败
		 */
		public void readSSE(SSEListener listener) throws IOException {
			try (InputStream is = huc.getInputStream();
					BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				String resp;
				while ((resp = reader.readLine()) != null) {
					// 少数网关会在首行带 BOM，先去掉，避免误判成“不是 SSE”
					if (!resp.isEmpty() && resp.charAt(0) == '\uFEFF')
						resp = resp.substring(1);
					// 空行是事件分隔符，以冒号开头的是注释/心跳
					if (resp.trim().isEmpty() || resp.startsWith(":"))
						continue;
					int idx = resp.indexOf(':');
					if (idx < 0)
						throw new IOException("响应不是 SSE 流，无法解析的行：" + abbreviate(resp));
					String field = resp.substring(0, idx).trim();
					// 不是 SSE 规范字段（data/event/id/retry）说明这不是事件流：
					// 典型情况是网关错误页、HTTP 200 的纯 JSON 错误体，必须报错而不是静默结束
					if (!isSseField(field))
						throw new IOException("响应不是 SSE 流，无法解析的行：" + abbreviate(resp));
					String dataElem = resp.substring(idx + 1);
					if (dataElem.startsWith(" "))
						dataElem = dataElem.substring(1);
					// event/id/retry 等字段与模型输出无关，交给业务反而会让 JSON 解析失败
					if (!"data".equals(field))
						continue;
					boolean keepReading;
					try {
						keepReading = listener.accept(field, dataElem);
					} catch (IOException e) {
						throw e;
					} catch (Throwable t) {
						throw new IOException("处理 SSE 数据失败：" + t, t);
					}
					if (!keepReading)
						break;
				}
			} catch (IOException ex) {
				generateExceptionFromError(ex);
			} finally {
				closeOutput();
				close();
			}
		}

		/** 截断过长的行，避免把整段错误页塞进异常信息与日志。 */
		private static String abbreviate(String line) {
			return line.length() > 200 ? line.substring(0, 200) + "…" : line;
		}

		/** 是否为 SSE 规范定义的字段名。 */
		private static boolean isSseField(String field) {
			return "data".equals(field) || "event".equals(field) || "id".equals(field) || "retry".equals(field);
		}
	}
	private HttpURLConnection openConn() throws IOException {
		URL url = new URL(this.url.toString());
		System.out.println(this.url.toString());
		HttpURLConnection huc = null;
		if(px==null)
			huc=(HttpURLConnection) url.openConnection();
		else
			huc=(HttpURLConnection) url.openConnection(px);
		huc.setInstanceFollowRedirects(followRedirect);
		
		huc.setConnectTimeout(30000);
		huc.setReadTimeout(readTimeoutMs);
		for(String[] header:headers) {
			huc.setRequestProperty(header[0], header[1]);
		}
		
		return huc;
	}
	/** post/get 沿用的默认读超时：流式生成可能在两段输出之间停顿，给足 30 分钟 */
	private static final int DEFAULT_STREAMING_READ_TIMEOUT=30*60*1000;
	/** 显式指定过读超时后，不再套用长超时 */
	private void applyDefaultStreamingReadTimeout() {
		if(!readTimeoutSet)
			readTimeoutMs=DEFAULT_STREAMING_READ_TIMEOUT;
	}
	/**
	 * 指定本次请求的读超时（毫秒），必须在 {@code post()}/{@code get()}/{@code open()} 之前调用。
	 * <p>
	 * 默认值：{@code post()}/{@code get()} 为 30 分钟，{@code open()} 为 30 秒。
	 * 流式请求（SSE）建议显式指定一个更短的超时：服务端若把连接挂住、一个字节都不返回，
	 * 默认的 30 分钟会让整轮对话一直停在“生成中”。
	 * </p>
	 *
	 * @param millis 读超时毫秒数，必须大于 0
	 * @return 当前实例
	 */
	public HttpRequestBuilder readTimeout(int millis) {
		if(millis<=0)
			throw new IllegalArgumentException("读超时必须大于 0");
		readTimeoutMs=millis;
		readTimeoutSet=true;
		return this;
	}
	public OpenedConnectionBuilder post(boolean doOutput) throws IOException {
		applyDefaultStreamingReadTimeout();
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("POST");
		huc.setDoOutput(doOutput);
		huc.setDoInput(true);
		huc.connect();
		return new OpenedConnectionBuilder(huc,doOutput);
		
	}
	public OpenedConnectionBuilder post() throws IOException {
		applyDefaultStreamingReadTimeout();
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("POST");
		huc.setDoOutput(true);
		huc.setDoInput(true);
		//huc.connect();
		return new OpenedConnectionBuilder(huc,true);
	}
	public OpenedConnectionBuilder get(boolean doOutput) throws IOException {
		applyDefaultStreamingReadTimeout();
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("GET");
		huc.setDoOutput(doOutput);
		huc.setDoInput(true);
		huc.connect();
		return new OpenedConnectionBuilder(huc,doOutput);
	}
	public OpenedConnectionBuilder get() throws IOException {
		applyDefaultStreamingReadTimeout();
		HttpURLConnection huc=openConn();
		huc.setRequestMethod("GET");
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
