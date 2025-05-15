package eu.tgx03.ffmpeg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Represents an ffmpeg command that can get executed
 * and takes prefixes, input arguments and output arguments.
 * See the respective classes for more details.
 */
public class Command implements Callable<List<String>> {

    /**
     * The executors to get process output
     */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * All arguments put directly behind the call, in front of any input files.
     */
    private final List<String> prefixes = new ArrayList<>();
    /**
     * A list of all input files and arguments like ss applied to them.
     */
    private final List<Input> inputs = new ArrayList<>();
    /**
     * All the outputs with their arguments.
     * This will usually be the longest, as it included all the encoding options, mappings and so on.
     */
    private final List<Output> outputs = new ArrayList<>();

    /**
     * Create a new command instance, which is empty
     * except for the hide_banner flag, which always gets set.
     */
    public Command() {
        prefixes.add("-hide_banner");   // Always get rid of the FFMPEG banner
    }

    /**
     * Add a prefix String. All the arguments are taken raw because I'm lazy.
     *
     * @param prefix The prefix argument.
     */
    public void addPrefix(String prefix) {
        prefixes.add(prefix);
    }

    /**
     * Add an input.
     *
     * @param input The input to add.
     */
    public void addInput(Input input) {
        inputs.add(input);
    }

    /**
     * Add an output.
     *
     * @param output The output to add.
     */
    public void addOutput(Output output) {
        outputs.add(output);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ffmpeg ");
        for (String prefix : prefixes) {
            builder.append(prefix).append(" ");
        }
        for (Input input : inputs) {
            builder.append(input).append(" ");
        }
        for (Output output : outputs) {
            builder.append(output).append(" ");
        }
        return builder.toString();
    }

    @Override
    public List<String> call() throws IOException, ExecutionException, InterruptedException, FFMPEGException {
        Process process = Runtime.getRuntime().exec(toString());
        EXECUTOR.submit(new StreamReader(process.getInputStream()));
        Future<List<String>> errorReader = EXECUTOR.submit(new StreamReader(process.getErrorStream()));    // FFMPEG writes output to stderr
        List<String> result = errorReader.get();
        if (process.exitValue() == 0) return result;
        else throw new FFMPEGException("FFMpeg exited with code " + process.exitValue(), result);
    }

    /**
     * This class is used to empty stdout and stderr from the FFMpeg process.
     */
    private static class StreamReader implements Callable<List<String>> {

        /**
         * The reader actually reading the stream.
         */
        private final BufferedReader reader;

        /**
         * Create a new reader which can then be submitted to empty stdout or stderr.
         *
         * @param stream The stream to read from.
         */
        public StreamReader(InputStream stream) {
            this.reader = new BufferedReader(new InputStreamReader(stream));
        }

        @Override
        public List<String> call() {
            return reader.lines().toList(); // Not sure if my assumption this will always exit when FFMpeg exits is correct.
        }
    }
}
