package de.tgx03;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * A class used to empty a string in a separate thread without actually doing something with the output
 */
public class StreamNuller extends Thread {

	/**
	 * The stream this is supposed to clear.
	 */
	private final InputStream stream;
	/**
	 * Used to make sure this Nuller doesn't run twice.
	 * As this isn't atomic, this isn't a perfect solution, but should suffice if used sensibly.
	 */
	private volatile boolean running = false;

	/**
	 * A thread object that empties the buffer of a given InputStream
	 * to make sure the program doesn't get blocked as the buffer fills up.
	 *
	 * @param input The input stream to empty.
	 */
	public StreamNuller(InputStream input) {
		stream = input;
		super.setDaemon(true);
		super.setPriority(Thread.MIN_PRIORITY);
	}

	/**
	 * Empties the provided input stream.
	 */
	@Override
	public void run() {
		if (running) return;
		running = true;
		InputStreamReader reader = new InputStreamReader(stream);
		char[] buffer = new char[1024];
		try {
			while (reader.read(buffer) != -1) ;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}