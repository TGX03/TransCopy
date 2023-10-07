package de.tgx03;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;

/**
 * A thread factory whose whole purpose is to create default virtual threads.
 * To state the obvious, the threads get created in an unstarted state.
 */
class VirtualThreadFactory implements ThreadFactory {

	/**
	 * The instance of this factory.
	 */
	private static final VirtualThreadFactory INSTANCE = new VirtualThreadFactory();

	/**
	 * Singleton and stuff.
	 */
	private VirtualThreadFactory() {}

	/**
	 * Returns the singleton instance of this factory.
	 * @return The instance of this factory.
	 */
	public static VirtualThreadFactory getINSTANCE() {
		return INSTANCE;
	}

	@Override
	public Thread newThread(@NotNull Runnable r) {
		return Thread.ofVirtual().unstarted(r);
	}
}
