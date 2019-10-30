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
	private boolean debug = false;
	private static Writer debugWriter;
	
	private static final int MAX_COPY_RETRIES = 3;

	private Main main;
	private Path source, target;
	private String style;
	private int num;
	private int folderCount;
	private boolean isWindows;
	private Map<WatchKey, Path> keys = new HashMap<WatchKey, Path>(1024);

	Watcher(Main main, Path source, Path target, String style, int num, boolean debug)
	{
		super("Watch thread " + num);
		this.main = main;
		this.source = source;
		this.target = target;
		this.style = style;
		this.num = num;
		this.debug = debug;

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
		if(!debug)
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
							if(Main.shouldSkipPath(path))
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
					main.markError();
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
							relative = null;
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
							if(Main.shouldSkipPath(relative))
							{
								continue eventLoop;
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
					main.markError();
					e.printStackTrace();
				}
			}
			finally
			{
				// Always mark error when exiting this thread.
				main.markError();
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
	 * @return True if completed without error 
	 */
	public boolean copy(Path path)
	{
		boolean[] errorState = { false };
		innerDelete(path, true, errorState);
		Path sourceCopy = source.resolve(path).normalize();
		Path targetCopy = target.resolve(path).normalize();

		main.addText("Copy");

		if(Files.isDirectory(sourceCopy))
		{
			try
			{
				Files.createDirectories(targetCopy.getParent());
				long start = System.currentTimeMillis();
				final boolean[] walkError = { false };
				Files.walkFileTree(sourceCopy, new FileVisitor<Path>()
				{
					private int dot;

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
						throws IOException
					{
						Path targetFile = target.resolve(source.relativize(file));
						try
						{
							copyWithRetry(file, targetFile);
						}
						catch(NoSuchFileException e)
						{
							// Indicates the file was deleted while copying, so ignore.
							walkError[0] = true;
						}
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
						if(Main.shouldSkipPath(source.relativize(dir)))
						{
							return FileVisitResult.SKIP_SUBTREE;
						}
						Path targetDir = target.resolve(source.relativize(dir));
						Files.createDirectories(targetDir);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult postVisitDirectory(Path dir, IOException e)
						throws IOException
					{
						if(e != null)
						{
							if (e instanceof NoSuchFileException)
							{
								walkError[0] = true;
							}
							else
							{
								throw e;
							}
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path arg0, IOException e)
						throws IOException
					{
						if (e instanceof NoSuchFileException)
						{
							walkError[0] = true;
						}
						else
						{
							throw e;
						}
						walkError[0] = true;
						return FileVisitResult.CONTINUE;
					}
				});
				if (walkError[0])
				{
					// NoSuchFileException, thrown if things are changing underfoot.
					main.addText(" PARTIAL ", "key");
				}
				else
				{
					main.addText(" OK ", "key");
				}
				showSlowTime(start);
			}
			catch(IOException e)
			{
				main.addText(" ERROR\n", "error");
				e.printStackTrace();
				if (debug) {
					main.addText("\n" + e.toString() + "\n");
				}
				return false;
			}
		}
		else
		{
			try
			{
				Files.createDirectories(targetCopy.getParent());
				long start = System.currentTimeMillis();
				copyWithRetry(sourceCopy, targetCopy);
				main.addText(" OK ", "key");
				showSlowTime(start);
			}
			catch(NoSuchFileException e)
			{
				// If the source file was already deleted, then ignore this
				// error as we do not need it to be copied now.
				main.addText(" ABSENT ", "key");
			}
			catch(IOException e)
			{
				// Other errors are shown as error.
				main.addText(" ERROR\n", "error");
				e.printStackTrace();
				if (debug) {
					main.addText("\n" + e.toString() + "\n");
				}
				return false;
			}
		}
		main.addText("\n");
		return !errorState[0];
	}

	/**
	 * Same as Files.copy, but retries for errors which were observed to be
	 * temporary due to simultaneous changes during the copy process.
	 * @param source Source path
	 * @param target Target path
	 * @throws IOException Exceptions that we don't retry for, or failed retries
	 */
	private static void copyWithRetry(Path source, Path target) throws IOException
	{
		IOException last = null;
		for(int retries = 0; retries < MAX_COPY_RETRIES; retries ++)
		{
			try
			{
				Files.copy(source, target);
				return;
			}
			catch(AccessDeniedException e)
			{
				last = e;
				try
				{
					sleep(50);
				}
				catch(InterruptedException e1)
				{
				}
			}
		}
		throw last;
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
	 * @return True if completed without error 
	 */
	public boolean delete(Path path)
	{
		boolean[] errorState = { false };
		if(innerDelete(path, false, errorState))
		{
			main.addText("\n");
		}
		return !errorState[0];
	}

	private boolean innerDelete(Path path, boolean displayAnyway, boolean[] errorState)
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
						errorState[0] = true;
						e.printStackTrace();
						if (debug) {
							main.addText("\n" + e.toString() + "\n");
						}
						return true;
					}
				}

				main.addText(" OK ", "key");
				showSlowTime(start);
			}
			catch(IOException e)
			{
				main.addText(" ERROR ", "error");
				errorState[0] = true;
				e.printStackTrace();
				if (debug) {
					main.addText("\n" + e.toString() + "\n");
				}
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
				errorState[0] = true;
				e.printStackTrace();
				if (debug) {
					main.addText("\n" + e.toString() + "\n");
				}
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