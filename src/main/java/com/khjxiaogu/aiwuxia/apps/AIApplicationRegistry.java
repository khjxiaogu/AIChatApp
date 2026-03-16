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

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
/**
 * AI 应用注册中心，用于管理不同类型的 AI 应用工厂，并根据元数据动态创建 AI 应用实例。
 * 该类采用单例模式（通过私有构造函数防止实例化），所有方法均为静态，提供全局的工厂注册和实例创建功能。
 */
public class AIApplicationRegistry {
	private static final Map<String,AIApplicationFactory> FACTORY=new HashMap<>();
	static{
		register("write",(base,folder,name,meta)->new AIArticleMain(folder));
		register("talk",(base,folder,name,meta)->new AICharaTalkMain(base,folder, name, meta));
		register("galgame",(base,folder,name,meta)->new AIGalgameMain(folder, name));
		register("group",(base,folder,name,meta)->new AIGroupApplication(folder, name,meta));
		register("sql",(base,folder,name,meta)->new AISQLMain(folder));
		register("trpg",(base,folder,name,meta)->new AITRPGSceneMain(base,folder, name, meta));
		register("wuxia",(base,folder,name,meta)->new AIWuxiaMain(folder));
		
	}
	private AIApplicationRegistry() {
	}
    /**
     * 根据传入的元数据创建 AI 应用实例。
     * 自动加载安装文件夹下的meta.json为元数据，从元数据中提取类型（"type"字段）和显示名称（"name"字段），
     * 然后从注册表中查找对应类型的工厂，调用工厂的 {@link AIApplicationFactory#createInstance(File, File, String, JsonObject)} 方法创建实例。
     *
     * @param basePath    服务基础路径（根目录），将传递给工厂
     * @param localFolder 当前应用的安装文件夹，将传递给工厂
     * @return 新创建的 {@link AIApplication} 实例
     * @throws Throwable 如果创建过程中发生错误，或找不到对应类型的工厂
     * @throws NoSuchElementException 如果元数据中的类型未注册
     */
	public static AIApplication createInstance(File basePath,File localFolder) throws Throwable {
		return createInstance(basePath,localFolder,null);
	}
    /**
     * 根据传入的元数据创建 AI 应用实例。
     * 从元数据中提取类型（"type"字段）和显示名称（"name"字段），
     * 然后从注册表中查找对应类型的工厂，调用工厂的 {@link AIApplicationFactory#createInstance(File, File, String, JsonObject)} 方法创建实例。
     *
     * @param basePath    服务基础路径（根目录），将传递给工厂
     * @param localFolder 当前应用的安装文件夹，将传递给工厂
     * @param metaData    应用的配置元数据，必须包含 "type" 和 "name" 字段，为null时自动加载安装文件夹下的meta.json
     * @return 新创建的 {@link AIApplication} 实例
     * @throws Throwable 如果创建过程中发生错误，或找不到对应类型的工厂
     * @throws NoSuchElementException 如果元数据中的类型未注册
     */
	public static AIApplication createInstance(File basePath,File localFolder,JsonObject metaData) throws Throwable {
		if(metaData==null) {
			File metaFile=new File(localFolder,"meta.json");
			metaData=JsonParser.parseString(FileUtil.readString(metaFile)).getAsJsonObject();
		}
		String type=metaData.get("type").getAsString();
		String name=metaData.get("name").getAsString();
		AIApplicationFactory factory=FACTORY.get(type);
		if(factory==null)
			throw new NoSuchElementException("no ai type named "+type);
		return factory.createInstance(basePath, localFolder, name, metaData);
	}
    /**
     * 注册一个 AI 应用工厂。
     * 将指定名称的工厂放入注册表中，如果该名称已经存在，则抛出运行时异常，防止重复注册。
     * 该方法为同步方法，确保线程安全。
     *
     * @param name    工厂对应的类型名称（应与元数据中的 "type" 字段匹配）
     * @param factory 要注册的 {@link AIApplicationFactory} 实例
     * @throws RuntimeException 如果该名称已经被注册
     */
	public synchronized static void register(String name,AIApplicationFactory factory) {
		if(!FACTORY.containsKey(name)) {
			FACTORY.put(name, factory);
			return;
		}
		throw new RuntimeException(name+" registered twice!");
	}
	

}
