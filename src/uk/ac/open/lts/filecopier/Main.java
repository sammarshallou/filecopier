/*
This file is part of filecopier.

filecopier is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

filecopier is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with filecopier. If not, see <http://www.gnu.org/licenses/>.

Copyright 2013 The Open University
*/
package uk.ac.open.lts.filecopier;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import javax.swing.*;
import javax.swing.text.*;

public class Main extends JFrame implements ActionQueue.Handler
{
	private JTextPane pane;
	private DefaultStyledDocument doc;
	private LinkedList<Watcher> watchers = new LinkedList<Watcher>();
	private Object startupSynch = new Object();
	private ActionQueue queue = new ActionQueue(this);
	private int displayLines = 0;
	
	private Image idleIcon, busyIcon;
	private boolean status = false, queueBusy = false;
	private Set<Watcher> waitingStartup = new HashSet<Watcher>();

	private static String VERSION = "1.05";
	private static int MAX_LINES = 500;

	/**
	 * Gets synch object used during startup to prevent multiple folder
	 * searches at once (non-Windows platforms only).
	 * @return Synch object 
	 */
	public Object getStartupSynch()
	{
		return startupSynch;
	}
	
	/**
	 * @return Action queue
	 */
	public ActionQueue getQueue()
	{
		return queue;
	}

	private final static Color[] COLORS =
	{
		Color.MAGENTA, Color.CYAN, Color.YELLOW
	}; 
	
	public final static HashSet<String> SKIP_FOLDERS = new HashSet<String>(
		Arrays.asList(new String[] { ".git" }));

	public Main()
	{
		super("FileCopier");
		getContentPane().setLayout(new BorderLayout());
		doc = new DefaultStyledDocument();
		doc.addStyle("_DEFAULT", null).addAttribute(StyleConstants.Foreground, Color.LIGHT_GRAY);
		doc.addStyle("white", null).addAttribute(StyleConstants.Foreground, Color.WHITE);
		doc.addStyle("key", null).addAttribute(StyleConstants.Foreground, Color.GREEN);
		doc.addStyle("error", null).addAttribute(StyleConstants.Foreground, Color.RED);
		doc.addStyle("slow", null).addAttribute(StyleConstants.Foreground, new Color(128, 0, 0));

		for(int i=0; i<COLORS.length; i++)
		{
			doc.addStyle("c" + i, null).addAttribute(StyleConstants.Foreground, COLORS[i]);
		}

		pane = new JTextPane(doc);
		pane.setBackground(Color.black);
		pane.setForeground(Color.white);
		pane.setFont(new Font("Lucida Console", Font.PLAIN, 13));
		pane.setEditable(false);

		JPopupMenu menu = new JPopupMenu("Commands");

		JMenuItem clear = new JMenuItem(new AbstractAction("Clear display") 
		{
			@Override
			public void actionPerformed(ActionEvent arg0)
			{
				try
				{
					doc.remove(0, doc.getLength());
				}
				catch(BadLocationException e)
				{
					e.printStackTrace();
				}
			}
		});
		menu.add(clear);

		JMenu wipeMenu = new JMenu("Wipe and re-copy");
		menu.add(wipeMenu);

		pane.setComponentPopupMenu(menu);

		JScrollPane scroll = new JScrollPane(pane, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, 
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		getContentPane().add(scroll, BorderLayout.CENTER);
		
		setSize(600, 800);
		idleIcon = Toolkit.getDefaultToolkit().createImage(getClass().getResource("icon.png"));
		busyIcon = Toolkit.getDefaultToolkit().createImage(getClass().getResource("icon.busy.png"));

		setState(JFrame.ICONIFIED);
		setVisible(true);
		
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				// TODO Make it not close, or else prompt, if it hasn't finished updating.
				System.exit(0);
			}
		});
		
		parseArgs(wipeMenu);

		updateStatus();
	}

	/**
	 * Settings lines must be of the form "c:\source => c:\target".
	 */
	private final static Pattern SETTINGS_REGEX = Pattern.compile(
		"^(.*[^ ]) ?=> ?([^ ].*)$");
	
	/**
	 * Parse the command-line arguments.
	 * @param wipeMenu Menu to add 'wipe server' options to
	 */
	private void parseArgs(JMenu wipeMenu)
	{
		addText("FileCopier ");
		addText(VERSION, "white");
		addText("\n\n");

		Path settings = FileSystems.getDefault().getPath(System.getProperty("user.home"), ".filecopier");
		try(BufferedReader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(settings.toFile()), "UTF-8")))
		{
			for(int index=1;; index++)
			{
				String line = reader.readLine();
				if(line == null)
				{
					break;
				}
				// Ignore blank lines or those that begin with #
				if(line.trim().equals("") || line.startsWith("#"))
				{
					continue;
				}
				Matcher m = SETTINGS_REGEX.matcher(line);
				if (!m.matches())
				{
					addError("Settings line does not match pattern (c:\\source => c:\\target): ", 
						"" + index);
					continue;
				}
				String sourceText = m.group(1).trim(), targetText = m.group(2).trim();
				FileSystem fileSystem = FileSystems.getDefault();
				final Path source = fileSystem.getPath(sourceText),
					target = fileSystem.getPath(targetText);
				if(!Files.exists(source))
				{
					addError("Source folder not found: ", sourceText);
					continue;
				}
				if(!Files.isDirectory(source))
				{
					addError("Source is not a folder: ", sourceText);
					continue;
				}
				if(!Files.exists(target))
				{
					addError("Target folder not found: ", targetText);
					continue;
				}
				if(!Files.isDirectory(target))
				{
					addError("Target is not a folder: ", targetText);
					continue;
				}
				if(!Files.isWritable(target))
				{
					addError("Target is not writable: ", targetText);
				}
				
				final Watcher watcher;
				synchronized(watchers)
				{
					watcher = new Watcher(this, source, target, "c" + (index % COLORS.length), index);
					watchers.add(watcher);
				}
				waitingStartup.add(watcher);
				final int finalNum = index;
				wipeMenu.add(new JMenuItem(new AbstractAction(finalNum + " " + source)
				{
					@Override
					public void actionPerformed(ActionEvent arg0)
					{
						watcher.wipe();
					}
				}));
			}
		}
		catch(IOException e)
		{
			addError("Unable to load configuration file: ", "" + settings); 
			return;
		}
	}
	
	void addError(String start, String text)
	{
		addText(start);
		addText(text, "error");
		addText("\n");
	}

	public void addText(final String text)
	{
		addText(text, "_DEFAULT");
	}

	public void addText(final String text, String style)
	{
		addText(text, doc.getStyle(style));
	}

	public void addText(final String text, final AttributeSet attributes)
	{
		Runnable r = new Runnable()
		{
			public void run()
			{
				try
				{
					int currentLength = doc.getLength();

					// Count number of lines.
					for(int i=0; i<text.length(); i++)
					{
						if(text.charAt(i) == '\n')
						{
							displayLines++;
						}
					}

					// If there's too many, delete text from the front.
					while(displayLines > MAX_LINES)
					{
						// Find first LF.
						int lf = -1;
						for(int pos = 0; pos < currentLength; pos+=128)
						{
							String text = doc.getText(0, 128);
							lf = text.indexOf('\n');
							if (lf != -1)
							{
								lf += pos;
								break;
							}
						}
						// This is not possible if there's at least one line.
						assert(lf != -1);

						// Delete up to and including LF.
						doc.remove(0, lf + 1);
						currentLength -= (lf + 1);
						displayLines --;
					}

					// Insert new string.
					doc.insertString(currentLength, text, attributes);
				}
				catch(BadLocationException e)
				{
					throw new Error(e);
				}
			}
		};
		if(SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}

	/**
	 * @param args 
	 */
	public static void main(final String[] args)
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				}
				catch(Exception e)
				{
					e.printStackTrace();
				}
				new Main();
			}
		});
	}
	
	@Override
	public void markBusy()
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			public void run()
			{
				queueBusy = true;
				updateStatus();
			}
		});
	}
	
	@Override
	public void markIdle()
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			public void run()
			{
				queueBusy = false;
				updateStatus();
			}
		});
	}
	
	private void updateStatus()
	{
		boolean busy = queueBusy || !waitingStartup.isEmpty();
		if(status != busy)
		{
			status = busy;
			setIconImage(busy ? busyIcon : idleIcon);
		}
	}
	
	public void startupFinished(final Watcher watcher)
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			public void run()
			{
				waitingStartup.remove(watcher);
				updateStatus();
			}
		});
	}
}
