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
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * AI对话窗口Swing实现
 * 布局说明：
 * - 左侧：聊天显示区（中心） + 自动增高输入框（底部）
 * - 右侧：垂直三块——历史总结（至少10行）、推理内容、固定5行Token分析
 */
public class AIChatWindow extends JFrame {

    /**
	 * 
	 */
	private static final long serialVersionUID = -295756251826212068L;
	// 左侧组件
    private JTextArea chatDisplayArea;      // 显示聊天历史（只读）
    private JTextArea inputArea;            // 输入框（自动增高）
    private JScrollPane inputScrollPane;    // 输入框的滚动面板，用于控制高度

    // 右侧组件
    private JTextArea summaryArea;           // 历史记录总结（右上）
    private JTextArea reasoningArea;         // 当前推理内容（右中）
    private JTextArea tokenArea;             // Token分析（右下，固定5行）

    // 常量定义
    private static final int MAX_INPUT_HEIGHT = 120;    // 输入框最大高度(像素)，约4-5行
    private static final int MIN_INPUT_HEIGHT = 30;      // 最小高度

    public AIChatWindow() {
        setTitle("AI 对话窗口");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 700);
        setLocationRelativeTo(null);

        // 初始化所有组件
        initLeftPanel();
        initRightPanel();

        // 使用JSplitPane支持左右区域调整大小
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createLeftPanel(), createRightPanel());
        splitPane.setResizeWeight(0.6); // 左侧占比60%
        splitPane.setOneTouchExpandable(true);

        add(splitPane, BorderLayout.CENTER);


        // 设置输入框自动增高监听
        setupAutoGrowInput();
    }
	public void setBackLog(String text,String reasoner) {
		chatDisplayArea.setText(text);
		if(reasoner!=null)
			reasoningArea.setText(reasoner);
		//oarea.setCaretPosition(text.length());
		SwingUtilities.invokeLater(()->{
			JScrollPane parentScroll = (JScrollPane) chatDisplayArea.getParent().getParent();
            if (parentScroll != null) {
                JScrollBar vertical = parentScroll.getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            }
		});
		/*exec.schedule(()->{
			JScrollBar scb=opane.getVerticalScrollBar();
			scb.setValue(scb.getMaximum());
		}, 100, TimeUnit.MILLISECONDS);
		*/
	}
	public void setStatus(String status) {
		summaryArea.setText(status);
	}
	public void setUsage(String status) {
		tokenArea.setText(status);
	}
    /**
     * 初始化左侧聊天区域组件
     */
    private void initLeftPanel() {
        // 聊天显示区（只读，带滚动条）
        chatDisplayArea = new JTextArea();
        chatDisplayArea.setEditable(false);
        chatDisplayArea.setLineWrap(true);
        chatDisplayArea.setWrapStyleWord(true);
        chatDisplayArea.setFont(new Font("SansSerif", Font.PLAIN, 14));

        // 输入框（自动增高，带滚动条）
        inputArea = new JTextArea();
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font("SansSerif", Font.PLAIN, 14));
        inputArea.setRows(1); // 初始1行

        // 输入框的滚动面板：垂直滚动条按需出现，水平从不
        inputScrollPane = new JScrollPane(inputArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setBorder(BorderFactory.createEtchedBorder());
    }

    /**
     * 初始化右侧三个区域组件
     */
    private void initRightPanel() {
        // 右上：历史总结（至少10行）
        summaryArea = new JTextArea();
        summaryArea.setEditable(false);
        summaryArea.setRows(10);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        summaryArea.setFont(new Font("SansSerif", Font.PLAIN, 13));

        // 右中：推理内容
        reasoningArea = new JTextArea();
        reasoningArea.setEditable(false);
        reasoningArea.setRows(10);
        reasoningArea.setLineWrap(true);
        reasoningArea.setWrapStyleWord(true);
        reasoningArea.setFont(new Font("SansSerif", Font.PLAIN, 13));

        // 右下：Token分析（固定5行）
        tokenArea = new JTextArea();
        tokenArea.setEditable(false);
        tokenArea.setRows(5);
        tokenArea.setLineWrap(true);
        tokenArea.setWrapStyleWord(true);
        tokenArea.setFont(new Font("Monospaced", Font.PLAIN, 12)); // 等宽字体便于数据对齐
    }

    /**
     * 构建左侧面板（聊天显示区 + 输入区 + 发送按钮）
     */
    private JPanel createLeftPanel() {
        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // 聊天显示区放入滚动面板
        JScrollPane chatScroll = new JScrollPane(chatDisplayArea);
        chatScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "聊天历史", TitledBorder.LEFT, TitledBorder.TOP));
        leftPanel.add(chatScroll, BorderLayout.CENTER);

        // 底部输入区域：输入滚动面板 + 发送按钮
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 0));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        // 发送按钮（简单功能：将输入框内容追加到聊天区）
        JButton sendButton = new JButton("发送");
        sendButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        sendButton.addActionListener(this::sendMessage);

        bottomPanel.add(inputScrollPane, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        leftPanel.add(bottomPanel, BorderLayout.SOUTH);
        return leftPanel;
    }

    /**
     * 构建右侧面板（三个垂直区域，使用GridBagLayout控制权重）
     */
    private JPanel createRightPanel() {
        JPanel rightPanel = new JPanel(new GridBagLayout());
        rightPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(2, 2, 2, 2);

        // 右上：历史总结 (weighty = 0, 不占用额外垂直空间，高度由preferredSize决定)
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        JScrollPane summaryScroll = new JScrollPane(summaryArea);
        summaryScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "状态栏", TitledBorder.LEFT, TitledBorder.TOP));
        rightPanel.add(summaryScroll, gbc);

        // 右中：推理内容 (weighty = 1.0, 占据所有剩余垂直空间)
        gbc.gridy = 1;
        gbc.weighty = 1.0;
        JScrollPane reasoningScroll = new JScrollPane(reasoningArea);
        reasoningScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "思考内容", TitledBorder.LEFT, TitledBorder.TOP));
        rightPanel.add(reasoningScroll, gbc);

        // 右下：Token分析 (weighty = 0, 固定高度，基于5行文本区域)
        gbc.gridy = 2;
        gbc.weighty = 0.0;
        JScrollPane tokenScroll = new JScrollPane(tokenArea);
        tokenScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Token消耗", TitledBorder.LEFT, TitledBorder.TOP));
        // 确保右下区域高度刚好适合5行 (由tokenArea的preferredSize决定，但JScrollPane可能会添加边框，我们通过设置tokenArea的rows来确保)
        rightPanel.add(tokenScroll, gbc);

        return rightPanel;
    }

    /**
     * 设置输入框自动增高：监听文档变化，调整inputScrollPane的首选高度
     */
    private void setupAutoGrowInput() {
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                adjustInputHeight();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                adjustInputHeight();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                adjustInputHeight();
            }
        });
        // 初始调整
        adjustInputHeight();
    }

    /**
     * 调整输入滚动面板的高度：基于输入框实际内容高度，但限制在MIN~MAX之间
     */
    private void adjustInputHeight() {
        // 获取输入框实际需要的高度（基于当前文本和宽度）
        int preferredHeight = inputArea.getPreferredSize().height;
        // 加上滚动面板的边框和内部间距（估算值，通常10-15像素）
        int extra = 10;
        int newHeight = Math.min(MAX_INPUT_HEIGHT, Math.max(MIN_INPUT_HEIGHT, preferredHeight + extra));

        // 只有高度变化时才更新，避免频繁revalidate
        Dimension currentSize = inputScrollPane.getPreferredSize();
        if (currentSize.height != newHeight) {
            inputScrollPane.setPreferredSize(new Dimension(currentSize.width, newHeight));
            // 重新验证父容器，使布局更新
            inputScrollPane.revalidate();
            // 注意：inputScrollPane的父容器是bottomPanel，其父容器是leftPanel，需要向上传递重新布局
            SwingUtilities.invokeLater(() -> {
                Container parent = inputScrollPane.getParent();
                while (parent != null) {
                    parent.revalidate();
                    parent = parent.getParent();
                }
            });
        }
    }

    /**
     * 发送消息：将输入框内容添加到聊天显示区，并清空输入框
     */
    private void sendMessage(ActionEvent e) {
        String message = inputArea.getText().trim();
        if (!message.isEmpty()) {
        	status=true;
        	inputArea.setEnabled(false);
        }
    }
    boolean status=false;
    public String showDialog() throws InterruptedException, ExecutionException {
    	status=false;
		inputArea.setEnabled(true);
		CompletableFuture<String> f=CompletableFuture.supplyAsync(()->{
			while(!status) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			String text=inputArea.getText();
			inputArea.setText("");
			return text;
		});

		return f.get();
	}

}