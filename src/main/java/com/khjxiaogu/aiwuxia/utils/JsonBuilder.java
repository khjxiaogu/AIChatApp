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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonBuilder {

	public JsonBuilder() {
	}
	public static JsonArrayBuilder<JsonArray> array(){
		return new JsonArrayBuilder<>(null);
	}
	public static JsonObjectBuilder<JsonObject> object(){
		return new JsonObjectBuilder<>(null);
	}
	public static class JsonArrayBuilder<T> {
		private JsonArray jo=new JsonArray();
		private T parent;
		private JsonArrayBuilder(T par) {
			parent=par;
		}
		@SuppressWarnings("unchecked")
		public T end() {
			if(parent==null)
				return (T) jo;
			return parent;
		}
		public JsonArrayBuilder<T> add(String v){
			jo.add(v);
			return this;
		}
		public JsonArrayBuilder<T> add(Number v){
			jo.add(v);
			return this;
		}
		public JsonArrayBuilder<T> add(boolean v){
			jo.add(v);
			return this;
		}
		public JsonArrayBuilder<T> add(Character v){
			jo.add(v);
			return this;
		}
		public JsonArrayBuilder<T> add(JsonElement v){
			jo.add(v);
			return this;
		}
		public JsonObjectBuilder<JsonArrayBuilder<T>> object(){
			JsonObjectBuilder<JsonArrayBuilder<T>> job= new JsonObjectBuilder<>(this);
			this.add(job.get());
			return job;
		}
		
		public int getSize() {
			return jo.size();
		}

		public JsonArrayBuilder<JsonArrayBuilder<T>> array(){
			JsonArrayBuilder<JsonArrayBuilder<T>> job= new JsonArrayBuilder<>(this);
			this.add(job.get());
			return job;
		}
		public JsonArray get() {
			return jo;
		};
		public String toString() {
			return jo.toString();
		}
	}
	public static class JsonObjectBuilder<T> {
		private JsonObject jo=new JsonObject();
		private T parent;
		private JsonObjectBuilder(T par) {
			parent=par;
		}
		
		@SuppressWarnings("unchecked")
		public T end() {
			if(parent==null)
				return (T) jo;
			return parent;
		}
		public JsonObjectBuilder<T> add(String k,String v){
			jo.addProperty(k,v);
			return this;
		}
		public JsonObjectBuilder<T> add(String k,Number v){
			jo.addProperty(k,v);
			return this;
		}
		public JsonObjectBuilder<T> add(String k,boolean v){
			jo.addProperty(k,v);
			return this;
		}
		public JsonObjectBuilder<T> add(String k,Character v){
			jo.addProperty(k, v);
			return this;
		}
		public JsonObjectBuilder<T> add(String k,JsonElement v){
			jo.add(k, v);
			return this;
		}
		public JsonObjectBuilder<JsonObjectBuilder<T>> object(String k){
			JsonObjectBuilder<JsonObjectBuilder<T>> job= new JsonObjectBuilder<JsonObjectBuilder<T>>(this);
			this.add(k, job.get());
			return job;
		}
		public JsonArrayBuilder<JsonObjectBuilder<T>> array(String k){
			JsonArrayBuilder<JsonObjectBuilder<T>> job= new JsonArrayBuilder<>(this);
			this.add(k,job.get());
			return job;
		}
		public JsonObject get() {
			return jo;
		};
		public String toString() {
			return jo.toString();
		}
	}
}
