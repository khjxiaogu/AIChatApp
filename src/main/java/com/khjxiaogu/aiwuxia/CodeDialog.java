package com.khjxiaogu.aiwuxia;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

public class CodeDialog extends JFrame{
	JSplitPane mpane;
	JSplitPane vpane;
	JTextArea area;
	JTextArea oarea;
	JTextArea sarea;
	JTextArea usage;
	JScrollPane opane;
	JButton send;
	boolean status=true;
	public CodeDialog(String title) {
		
		area = new JTextArea(2, 80);
		area.setFont(new Font("微软雅黑", Font.BOLD, 12));
		area.setLineWrap(true);
		JScrollPane pane = new JScrollPane(area);
		JPanel p = new JPanel();
		p.setLayout(new BorderLayout());
		p.add(pane,BorderLayout.CENTER);
		send=new JButton("发送");
		p.add(send,BorderLayout.EAST);
		send.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				status=true;
				send.setEnabled(false);
			}
			
		});;
		send.setEnabled(false);
		// pane.setPreferredSize(new Dimension(200,200));
		oarea = new JTextArea(46, 80);
		oarea.setLineWrap(true);
		oarea.setFont(new Font("微软雅黑", Font.BOLD, 12));
		opane = new JScrollPane(oarea);
		// opane.setPreferredSize(new Dimension(200,200));
		sarea = new JTextArea(46, 40);
		sarea.setFont(new Font("微软雅黑", Font.BOLD, 12));
		sarea.setLineWrap(true);
		JSplitPane s = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		s.add(sarea);
		usage=new JTextArea(4,40);
		usage.setEditable(false);
		usage.setVisible(true);
		s.setResizeWeight(1.0);
		s.setDividerLocation(0.7);
		s.add(usage);
		vpane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		vpane.setResizeWeight(1.0);
		vpane.setDividerLocation(0.7);
		mpane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		mpane.setResizeWeight(1.0);
		mpane.setDividerLocation(0.7);
		mpane.add(opane);
		mpane.add(p);
		vpane.add(mpane);
		vpane.add(s);
		this.add(vpane);
		this.setTitle( title);
		pack();

		setMinimumSize(getSize());
		setLocationRelativeTo(null);
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		this.show();
		
	}

	//ScheduledExecutorService exec=Executors.newScheduledThreadPool(1);
	public void setBackLog(String text) {
		oarea.setText(text);
		//oarea.setCaretPosition(text.length());
		SwingUtilities.invokeLater(()->{
			JScrollBar scb=opane.getVerticalScrollBar();
			scb.setValue(scb.getMaximum());
		});
		/*exec.schedule(()->{
			JScrollBar scb=opane.getVerticalScrollBar();
			scb.setValue(scb.getMaximum());
		}, 100, TimeUnit.MILLISECONDS);
		*/
	}
	public void append(String text) {
		setBackLog(oarea.getText()+text);
	}
	protected String showDialog() throws InterruptedException, ExecutionException {
		status=false;
		send.setEnabled(true);
		CompletableFuture<String> f=CompletableFuture.supplyAsync(()->{
			while(!status) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			String text=area.getText();
			area.setText("");
			return text;
		});

		return f.get();
	}

	public static Reader file() throws FileNotFoundException {
		JFileChooser chooser = new JFileChooser();
		if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
			return new BufferedReader(new FileReader(chooser.getSelectedFile()));
		else
			throw new FileNotFoundException("no file specified");
	}
	
	public String getBackLog() {
		return oarea.getText();
	}
}
