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

import java.io.*;
import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Thread watches one folder.
 */
class Watcher extends Thread
{
	private static boolean DEBUG = false;
	private static Writer debugWriter;

	private Main main;
	private Path source, target;
	private String style;
	private int num;
	private int folderCount;
	private boolean isWindows;
	private Map<WatchKey, Path> keys = new HashMap<WatchKey, Path>(1024);

	Watcher(Main main, Path source, Path target, String style, int num)
	{
		super("Watch thread " + num);
		this.main = main;
		this.source = source;
		this.target = target;
		this.style = style;
		this.num = num;

		start();
	}

	private void addIdent()
	{
		main.addText(num + " ", style);
	}

	public Path getSource()
	{
		return source;
	}

	public Path getTarget()
	{
		return target;
	}

	private void debugLog(Path relative, Kind<?> kind)
	{
		if(!DEBUG)
		{
			return;
		}

		try
		{
			if(debugWriter == null)
			{
				debugWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
					new File(System.getProperty("user.home"), "filecopier.debug.log")), "UTF-8"));
			}
			debugWriter.write("[EVENT] " + kind + ": " + relative + "\n");
			debugWriter.flush();
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}

	@Override
	public void run()
	{
		// Display info.
		synchronized(main)
		{
			addIdent();
			main.addText(source.toString(), "key");
			main.addText(" => ");
			main.addText(target.toString(), "key");
			main.addText("\n");
		}

		try(final WatchService service = source.getFileSystem().newWatchService())
		{
			// Start watching.
			final Watcher finalThis = this;
			final WatchEvent.Kind<?>[] kinds =
			{
				StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_DELETE,
				StandardWatchEventKinds.ENTRY_MODIFY
			};
			// In Windows this cannot be implemented without using the Windows-specific
			// FILE_TREE modifier. http://stackoverflow.com/questions/6255463/
			try
			{
				Class<?> c = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
				WatchEvent.Modifier[] modifiers = new WatchEvent.Modifier[]
				{
					(WatchEvent.Modifier)(c.getField("FILE_TREE").get(null))
				};
				WatchKey key = source.register(service, kinds, modifiers);
				keys.put(key, source);
				finalThis.isWindows = true;
			}
			catch(ClassNotFoundException e)
			{
				// Synchronization so that it does one folder at a time rather than
				// trying to do them all at once, which might be slower diskwise.
				synchronized(main.getStartupSynch())
				{
					Files.walkFileTree(source, new SimpleFileVisitor<Path>()
					{
						@Override
						public FileVisitResult preVisitDirectory(Path path,
							BasicFileAttributes attr) throws IOException
						{
							if(Main.SKIP_FOLDERS.contains(path.getFileName().toString()))
							{
								return FileVisitResult.SKIP_SUBTREE;
							}
							WatchKey key = path.register(service, kinds);
							keys.put(key, path);
							finalThis.folderCount++;
							return FileVisitResult.CONTINUE;
						}
					});
				}
			}
			catch(Exception e)
			{
				synchronized(main)
				{
					addIdent();
					main.addError("Error starting watcher", e.getMessage());
				}
				return;
			}

			// Display to indicate that it's ready.
			synchronized(main)
			{
				addIdent();
				if (isWindows)
				{
					main.addText("Watching subtree (");
					main.addText("Windows", "key");
					main.addText(" mode)\n");
				}
				else
				{
					main.addText("Watching ");
					main.addText(folderCount + "", "key");
					main.addText(" folders\n");
				}
			}
			main.startupFinished(this);

			try
			{
				while (true)
				{
					// Block until events are present.
					WatchKey key = service.take();

					// Read all events.
					eventLoop: for(WatchEvent<?> event : key.pollEvents())
					{
						Path sourcePath, relative;
						if(event.context() == null)
						{
							sourcePath = null;
							relative = source.relativize(sourcePath);
						}
						else
						{
							sourcePath = keys.get(key).resolve(event.context().toString());
							relative = source.relativize(sourcePath);
						}
						Kind<?> kind = event.kind();
						debugLog(relative, kind);
						if(relative != null)
						{
							if(relative.isAbsolute() || relative.startsWith(".."))
							{
								// Should not get results outside the source folder.
								throw new Exception("Unexpected path " + sourcePath);
							}
							// Don't do folders we are skipping.
							for(int i=0; i<relative.getNameCount(); i++)
							{
								if(Main.SKIP_FOLDERS.contains(relative.getName(i).toString()))
								{
									continue eventLoop;
								}
							}
						}
						else
						{
							if(!event.kind().equals(StandardWatchEventKinds.OVERFLOW))
							{
								throw new Exception("Unexpected null path for event kind " + event.kind());
							}
						}
						if(event.kind().equals(StandardWatchEventKinds.ENTRY_CREATE))
						{
							main.getQueue().copy(this, relative);
						}
						else if(event.kind().equals(StandardWatchEventKinds.ENTRY_DELETE))
						{
							main.getQueue().delete(this, relative);
						}
						else if(event.kind().equals(StandardWatchEventKinds.ENTRY_MODIFY))
						{
							// 'Modify' for directories is ignored.
							if(!Files.isDirectory(sourcePath))
							{
								main.getQueue().copy(this, relative);
							}
						}
						else if(event.kind().equals(StandardWatchEventKinds.OVERFLOW))
						{
							// This should re-copy everything.
							main.getQueue().copy(this, source.relativize(source));
						}
					}

					if(!key.reset())
					{
						key.cancel();
					}
				}
			}
			catch(Exception e)
			{
				synchronized(main)
				{
					addIdent();
					main.addError("Error watching", e.getMessage());
					e.printStackTrace();
				}
			}
		}
		catch(IOException e)
		{
			synchronized(main)
			{
				addIdent();
				main.addError("Error creating watch service", e.getMessage());
			}
		}
	}

	/**
	 * Called to cause the entire folder to be wiped and re-copied.
	 */
	public void wipe()
	{
		main.getQueue().copy(this, FileSystems.getDefault().getPath("."));
	}

	/**
	 * Deletes contents of the target path and re-copies it from the source path.
	 *
	 * This method is called on the QUEUE thread not the watcher thread.
	 *
	 * @param path Relative path
	 */
	public void copy(Path path)
	{
		innerDelete(path, true);
		Path sourceCopy = source.resolve(path);
		Path targetCopy = target.resolve(path);

		main.addText("Copy");

		if(Files.isDirectory(sourceCopy))
		{
			try
			{
				Files.createDirectories(targetCopy.getParent());
				long start = System.currentTimeMillis();
				Files.walkFileTree(sourceCopy, new SimpleFileVisitor<Path>()
				{
					private int dot;

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
						throws IOException
					{
						Path targetFile = target.resolve(source.relativize(file));
						Files.copy(file, targetFile);
						dot++;
						if(dot >= 100)
						{
							dot = 0;
							main.addText(" .");
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult preVisitDirectory(Path dir,
						BasicFileAttributes attrs) throws IOException
					{
						if(Main.SKIP_FOLDERS.contains(dir.getFileName().toString()))
						{
							return FileVisitResult.SKIP_SUBTREE;
						}
						Path targetDir = target.resolve(source.relativize(dir));
						Files.createDirectories(targetDir);
						return FileVisitResult.CONTINUE;
					}
				});
				main.addText(" OK ", "key");
				showSlowTime(start);
			}
			catch(IOException e)
			{
				main.addText(" ERROR\n", "error");
				e.printStackTrace();
				return;
			}
		}
		else
		{
			try
			{
				Files.createDirectories(targetCopy.getParent());
				long start = System.currentTimeMillis();
				Files.copy(sourceCopy, targetCopy);
				main.addText(" OK ", "key");
				showSlowTime(start);
			}
			catch(IOException e)
			{
				// If the source file was already deleted, then ignore this
				// error as we do not need it to be copied now.
				if (!Files.exists(sourceCopy))
				{
					main.addText(" ABSENT ", "key");
				}
				else
				{
					main.addText(" ERROR\n", "error");
					e.printStackTrace();
					return;
				}
			}
		}
		main.addText("\n");
	}

	private void showSlowTime(long start)
	{
		long time = System.currentTimeMillis() - start;
		if (time > 500)
		{
			main.addText(time + "", "slow");
			main.addText("ms ");
		}
	}

	/**
	 * Deletes contents of the target path.
	 *
	 * This method is called on the QUEUE thread not the watcher thread.
	 *
	 * @param path Relative path
	 */
	public void delete(Path path)
	{
		if(innerDelete(path, false))
		{
			main.addText("\n");
		}
	}

	private boolean innerDelete(Path path, boolean displayAnyway)
	{
		Path targetCopy = target.resolve(path);

		// Skip if it doesn't exist or if you're trying to delete the root folder
		boolean exists = Files.exists(targetCopy);
		boolean isRoot = false;
		if(exists)
		{
			try
			{
				isRoot = Files.isSameFile(target, targetCopy);
			}
			catch(IOException e)
			{
				throw new Error(e);
			}
		}
		if(!exists)
		{
			if(displayAnyway)
			{
				addIdent();
				if(path.toString().equals("."))
				{
					main.addText("Recopy", "key");
				}
				else
				{
					main.addText(path.toString(), "white");
				}
				main.addText(" - ");
			}
			return false;
		}

		addIdent();
		if(path.toString().equals("."))
		{
			main.addText("Recopy", "key");
		}
		else
		{
			main.addText(path.toString(), "white");
		}
		main.addText(" - Delete");
		if(Files.isDirectory(targetCopy))
		{
			try
			{
				long start = System.currentTimeMillis();

				// Delete children.
				Files.walkFileTree(targetCopy, new SimpleFileVisitor<Path>()
				{
					private int dot;

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
						throws IOException
					{
						deleteIfPresent(file);
						dot++;
						if(dot >= 100)
						{
							dot = 0;
							main.addText(" .");
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult postVisitDirectory(Path path, IOException e)
						throws IOException
					{
						if(e != null)
						{
							throw e;
						}
						deleteIfPresent(path);
						return FileVisitResult.CONTINUE;
					}
				});

				// Delete folder itself - except root folder.
				if(!isRoot)
				{
					try
					{
						deleteIfPresent(targetCopy);
					}
					catch(IOException e)
					{
						main.addText(" ERROR ", "error");
						e.printStackTrace();
						return true;
					}
				}

				main.addText(" OK ", "key");
				showSlowTime(start);
			}
			catch(IOException e)
			{
				main.addText(" ERROR ", "error");
				e.printStackTrace();
				return true;
			}
		}
		else
		{
			try
			{
				long start = System.currentTimeMillis();
				deleteIfPresent(targetCopy);
				main.addText(" OK ", "key");
				showSlowTime(start);
			}
			catch(IOException e)
			{
				main.addText(" ERROR ", "error");
				e.printStackTrace();
				return true;
			}
		}
		return true;
	}

	private static void deleteIfPresent(Path file) throws IOException
	{
		try
		{
			Files.delete(file);
		}
		catch(IOException e)
		{
			// If the error is 'file didn't exist anyway', ignore it.
			if(Files.exists(file))
			{
				throw e;
			}
		}
	}
}