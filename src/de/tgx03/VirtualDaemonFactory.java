package de.tgx03;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;

/**
 * A thread factory whose whole purpose is to create virtual threads as daemons for ThreadPools.
 * To state the obvious, the threads get created in an unstarted state.
 */
public class VirtualDaemonFactory implements ThreadFactory {

	/**
	 * The instance of this factory.
	 */
	private static final VirtualDaemonFactory INSTANCE = new VirtualDaemonFactory();

	/**
	 * Singleton and stuff.
	 */
	private VirtualDaemonFactory() {}

	/**
	 * Returns the singleton instance of this factory.
	 * @return The instance of this factory.
	 */
	public static VirtualDaemonFactory getINSTANCE() {
		return INSTANCE;
	}

	@Override
	public Thread newThread(@NotNull Runnable r) {
		Thread thread = Thread.ofVirtual().unstarted(r);
		thread.setDaemon(true);
		return thread;
	}
}
