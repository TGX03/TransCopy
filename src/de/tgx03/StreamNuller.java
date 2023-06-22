package de.tgx03;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * A class used to empty a string in a separate thread without actually doing something with the output
 */
public class StreamNuller extends Thread {

    /**
     * A thread object that empties the buffer of a given InputStream
     * to make sure the program doesn't get blocked as the buffer fills up.
     * @param input The input stream to empty.
     */
    public StreamNuller(InputStream input) {
        super(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            char[] buffer = new char[1024];
            try {
                while (reader.read(buffer) != -1) ;
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        super.setDaemon(true);
        super.setPriority(Thread.MIN_PRIORITY);
    }
}