package de.tgx03;

import java.util.concurrent.ThreadFactory;

/**
 * A thread factory whose whole purpose is to create virtual threads as daemons for ThreadPools.
 * To state the obvious, the threads get created in an unstarted state.
 */
public final class VirtualThreadFactory {

	/**
	 * A thread factory that creates virtual threads.
	 */
	public static final ThreadFactory VIRTUAL_FACTORY = r -> Thread.ofVirtual().unstarted(r);

	/**
	 * Singleton and stuff.
	 */
	private VirtualThreadFactory() {
	}
}
