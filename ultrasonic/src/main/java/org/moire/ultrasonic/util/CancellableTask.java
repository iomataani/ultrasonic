/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * @author Sindre Mehus
 * @version $Id$
 */
public abstract class CancellableTask
{
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final AtomicReference<Thread> thread = new AtomicReference<Thread>();
	private final AtomicReference<OnCancelListener> cancelListener = new AtomicReference<OnCancelListener>();

	public void cancel()
	{
		Timber.i("Cancelling %s", CancellableTask.this);
		cancelled.set(true);

		OnCancelListener listener = cancelListener.get();
		if (listener != null)
		{
			try
			{
				listener.onCancel();
			}
			catch (Throwable x)
			{
				Timber.w(x, "Error when invoking OnCancelListener.");
			}
		}
	}

	public boolean isCancelled()
	{
		return cancelled.get();
	}

	public void setOnCancelListener(OnCancelListener listener)
	{
		cancelListener.set(listener);
	}

	public boolean isRunning()
	{
		return running.get();
	}

	public abstract void execute();

	public void start()
	{
		thread.set(new Thread()
		{
			@Override
			public void run()
			{
				running.set(true);
				Timber.i("Starting thread for %s", CancellableTask.this);
				try
				{
					execute();
				}
				finally
				{
					running.set(false);
					Timber.i("Stopping thread for %s", CancellableTask.this);
				}
			}
		});
		thread.get().start();
	}

	public interface OnCancelListener
	{
		void onCancel();
	}
}