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

import java.nio.file.Path;
import java.util.*;

public class ActionQueue extends Thread
{
	/** 
	 * Delay this many ms to allow events to be grouped together. 
	 */
	private final static long ACTION_DELAY = 100;
	
	private LinkedList<Action> queue = new LinkedList<Action>();
	
	private Handler handler;

	/**
	 * Interface for owner of queue.
	 */
	public interface Handler
	{
		/**
		 * Called when the ActionQueue is idle.
		 * 
		 * Called from the action queue thread. 
		 */
		public void markBusy();
		
		/**
		 * Called when the ActionQueue is idle.
		 * 
		 * Called from the action queue thread. 
		 */
		public void markIdle();

		/**
		 * Called if an error occurs.
		 */
		public void markError();
	}

	private abstract static class Action
	{
		protected Watcher watcher;
		protected Path path;
		private long due;

		Action(Watcher watcher, Path path)
		{
			due = System.currentTimeMillis() + ACTION_DELAY;
			this.watcher = watcher;
			this.path = path;
		}

		long getDelay()
		{
			return due - System.currentTimeMillis();
		}

		abstract boolean apply();
		abstract boolean makesUnnecessary(Action futureAction);
		abstract boolean madeUnnecessary(Action futureAction);
	}

	private static class CopyAction extends Action
	{
		CopyAction(Watcher watcher, Path path)
		{
			super(watcher, path);
		}

		@Override
		boolean apply()
		{
			return watcher.copy(path);
		}

		@Override
		boolean makesUnnecessary(Action futureAction)
		{
			// Future copy AND delete are unnecessary for anything inside this path
			// because the copy process deletes and recopies.
			if(futureAction.path.startsWith(path))
			{
				return true;
			}

			return false;
		}

		boolean madeUnnecessary(Action futureAction)
		{
			// Copy is unnecessary if a future delete includes this path.
			if(futureAction instanceof DeleteAction)
			{
				if(path.startsWith(futureAction.path))
				{
					return true;
				}
			}
			return false;
		}
	}

	private static class DeleteAction extends Action
	{
		DeleteAction(Watcher watcher, Path path)
		{
			super(watcher, path);
		}

		@Override
		boolean apply()
		{
			return watcher.delete(path);
		}

		@Override
		boolean makesUnnecessary(Action futureAction)
		{
			// Doesn't make anything unnecessary; even if there is a future delete,
			// there might be a future copy before it so it would still be needed.
			return false;
		}

		boolean madeUnnecessary(Action futureAction)
		{
			// Delete is unnecessary if a future copy includes this path, because
			// copy always deletes the entire path.
			if(futureAction instanceof CopyAction)
			{
				if(path.startsWith(futureAction.path))
				{
					return true;
				}
			}
			return false;
		}
	}

	public void copy(Watcher watcher, Path fileOrFolder)
	{
		synchronized(queue)
		{
			queue.addLast(new CopyAction(watcher, fileOrFolder));
			queue.notifyAll();
		}
	}

	public void delete(Watcher watcher, Path fileOrFolder)
	{
		synchronized(queue)
		{
			queue.addLast(new DeleteAction(watcher, fileOrFolder));
			queue.notifyAll();
		}
	}
	
	public ActionQueue(Handler handler)
	{
		super("Action queue");
		this.handler = handler;
		start();
	}
	
	@Override
	public void run()
	{
		try
		{
			boolean busy = false;
			actionLoop: while(true)
			{
				Action first;
				synchronized(queue)
				{
					// Wait for event in queue.
					while(queue.isEmpty())
					{
						if(busy)
						{
							busy = false;
							handler.markIdle();
						}
						queue.wait();
					}
	
					if(!busy)
					{
						busy = true;
						handler.markBusy();
					}

					// Wait until event is due.
					first = queue.removeFirst();
					while(true)
					{
						long delay = first.getDelay();
						if(delay <= 0)
						{
							break;
						}
						queue.wait(delay);
					}
	
					// Check if this event is made unnecessary by future events.
					for(Action futureAction : queue)
					{
						if(first.madeUnnecessary(futureAction))
						{
							continue actionLoop;
						}
					}
	
					// Check if there are future events in the queue which are made
					// unnecessary by this event.
					for(Iterator<Action> i = queue.iterator(); i.hasNext();)
					{
						if(first.makesUnnecessary(i.next()))
						{
							i.remove();
						}
					}
				}
	
				// Carry out action
				if(!first.apply())
				{
					handler.markError();
				}
			}
		}
		catch(InterruptedException e)
		{
			// If interrupted, there is not a lot we can do, so exit.
			System.exit(0);
		}
		finally
		{
			// If this thread ends, indicate error
			handler.markError();
		}
	}
}
