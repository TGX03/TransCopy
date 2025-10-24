package eu.tgx03.ffmpeg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents an output file or stream for an FFMpeg command alongside its associated arguments.
 * The output can include encoding options, filters, and other configurations.
 */
public class Output implements ToArray<String> {

    /**
     * A list of arguments associated with the output. These arguments represent options,
     * configurations, and filters that can be applied to the output in an FFMpeg command.
     */
    private final List<String> arguments = new ArrayList<>();
    /**
     * Represents the output destination of an FFMpeg command.
     * This can be a file path or a special identifier, such as a stream destination.
     */
    private final String output;

    /**
     * Constructs an Output instance for the specified output destination.
     *
     * @param output The output destination of an FFMpeg command. This can be a file path
     *               or a special identifier, such as a stream destination.
     */
    public Output(String output) {
        this.output = output;
    }

    /**
     * Constructs an Output instance for the specified output destination and additional arguments.
     *
     * @param output    The output destination of an FFMpeg command. This can be a file path
     *                  or a special identifier, such as a stream destination.
     * @param arguments Additional arguments to apply to this output. These represent options,
     *                  configurations, or filters associated with the output in an FFMpeg command.
     */
    public Output(String output, String... arguments) {
        this.output = output;
        Collections.addAll(this.arguments, arguments);
    }

    /**
     * Wraps the given string in double quotes.
     *
     * @param string The string to be wrapped in double quotes.
     * @return A new string with the input wrapped in double quotes.
     */
    private static String enquote(String string) {
        return "\"" + string + "\"";
    }

    /**
     * Adds an argument to be applied to this Output.
     *
     * @param argument The argument to be added.
     */
    public void addArgument(String argument) {
        this.arguments.addAll(Arrays.asList(argument.split(" ")));
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (String argument : arguments) {
            builder.append(" ").append(argument);
        }
        builder.append(" ").append(enquote(output));
        return builder.toString();
    }

    /**
     * Returns an array which contains all arguments correctly split for usage by ProcessBuilder,
     * meaning every positional argument has its own slot in the array.
     * This also means no enquoting is necessary for spaces.
     *
     * @return An array of all the arguments in this object.
     */
    @Override
    public String[] toArray() {
        String[] result = new String[this.arguments.size() + 1];
        this.arguments.toArray(result);
        result[result.length - 1] = this.output;
        return result;
    }
}
