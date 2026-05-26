package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class CrontabMcp {
	public static class TimedTodoManager {
	    private final List<TodoItem> items;
	    private final Gson gson;
	    private final File dataFile;
	    private final Consumer<String> triggerConsumer;
	    private final ScheduledExecutorService scheduler;
	    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";
	    private static class TodoItem {
	        String description;
	        Date triggerTime; 

	        public TodoItem(String description, Date triggerTime) {
	            this.description = description;
	            this.triggerTime = triggerTime;
	        }

	        @Override
	        public String toString() {
	            return String.format("待办: %s | 触发时间: %s",
	                    description, new SimpleDateFormat(DATE_PATTERN).format(triggerTime));
	        }
	    }

	    public TimedTodoManager(File storageFilePath, Consumer<String> triggerConsumer) throws IOException {
	        this.triggerConsumer = triggerConsumer;
	        this.dataFile = storageFilePath;
	        File parentDir = dataFile.getParentFile();
	        if (parentDir != null && !parentDir.exists()) {
	            parentDir.mkdirs();
	        }
	        this.gson = new GsonBuilder()
	                .setDateFormat(DATE_PATTERN)
	                .setPrettyPrinting()
	                .create();
	        this.items = loadFromFile();
	        this.scheduler = Executors.newSingleThreadScheduledExecutor();
	        startMonitor();
	    }

	    /**
	     * 从JSON文件加载待办列表
	     */
	    private List<TodoItem> loadFromFile() {
	        if (!dataFile.exists()) {
	            return new ArrayList<>();
	        }
	        try (Reader reader = new FileReader(dataFile)) {
	            Type type = new TypeToken<List<TodoItem>>() {}.getType();
	            List<TodoItem> list = gson.fromJson(reader, type);
	            return list != null ? list : new ArrayList<>();
	        } catch (Exception e) {
	            System.err.println("加载数据文件失败，将使用空列表: " + e.getMessage());
	            return new ArrayList<>();
	        }
	    }

	    /**
	     * 保存当前待办列表到JSON文件（线程安全，调用处需持有对象锁）
	     */
	    private void saveToFile() {
	        try (Writer writer = new FileWriter(dataFile)) {
	            gson.toJson(items, writer);
	        } catch (IOException e) {
	            System.err.println("保存待办数据失败: " + e.getMessage());
	        }
	    }

	    /**
	     * 添加待办
	     * @param description 描述文本
	     * @param triggerTime 触发时间
	     * @return 是否添加成功（时间不能为过去？允许添加已过期待办，下次扫描会立即触发）
	     */
	    public synchronized boolean addTodo(String description, Date triggerTime) {
	        if (description == null || description.trim().isEmpty()) {
	            System.err.println("待办描述不能为空");
	            return false;
	        }
	        if (triggerTime == null) {
	            System.err.println("触发时间不能为空");
	            return false;
	        }
	        TodoItem newItem = new TodoItem(description, triggerTime);
	        items.add(newItem);
	        saveToFile();
	        System.out.println("已添加待办: " + newItem);
	        return true;
	    }
	    public synchronized List<TodoItem> getAllTodos() {
	        return new ArrayList<>(items);
	    }

	    private void checkAndTrigger() {
	        List<TodoItem> toTrigger = new ArrayList<>();
	        synchronized (this) {
	            // 找出所有已超时的待办（触发时间 <= 当前时间）
	            Date now = new Date();
	            Iterator<TodoItem> iterator = items.iterator();
	            while (iterator.hasNext()) {
	                TodoItem item = iterator.next();
	                if (!item.triggerTime.after(now)) { // 已到期
	                    toTrigger.add(item);
	                    iterator.remove();
	                }
	            }
	            if (!toTrigger.isEmpty()) {
	                // 有触发项，保存更新后的列表
	                saveToFile();
	            }
	        }

	        // 在锁外触发回调，避免回调中可能的长时间操作影响其他待办检查
	        for (TodoItem item : toTrigger) {
	            try {
	                triggerConsumer.accept(item.description);
	                System.out.println("待办已触发并删除: " + item.description);
	            } catch (Exception e) {
	                System.err.println("触发待办回调时发生异常: " + e.getMessage());
	            }
	        }
	    }

	    private void startMonitor() {
	        scheduler.scheduleAtFixedRate(this::checkAndTrigger, 0, 1, TimeUnit.SECONDS);
	    }

	    public void shutdown() {
	        scheduler.shutdown();
	        try {
	            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
	                scheduler.shutdownNow();
	            }
	        } catch (InterruptedException e) {
	            scheduler.shutdownNow();
	            Thread.currentThread().interrupt();
	        }
	        System.out.println("定时待办管理器已关闭");
	    }
	}
	public static MCPTools create(File path,Consumer<String> trigger) throws IOException {
		TimedTodoManager manager=new TimedTodoManager(path, trigger);
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("timed_trigger", "创建一个定时触发器，达到设定的时间时会触发，可用于定时提醒或定时任务。")
				.putParam("alert_time", "触发时间，格式是yyyy-mm-dd HH:MM:SS，系统本地时间")
				.putParam("note", "触发器事项，在触发时会写入到上下文，需要详细描述任务内容。")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					SimpleDateFormat format=new SimpleDateFormat("yyyy-mm-dd HH:MM:SS");
					Date date;
					try {
						date = format.parse(jo.get("alert_time").getAsString());
					} catch (ParseException e) {
						e.printStackTrace();
						return "日期格式错误";
					}
					
					manager.addTodo(jo.get("note").getAsString(), date);
					return "待办设置成功";
				}).build());
		return tools;
	}
}
