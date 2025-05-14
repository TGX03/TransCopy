package eu.tgx03.ffmpeg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an output file or stream for an FFMpeg command alongside its associated arguments.
 * The output can include encoding options, filters, and other configurations.
 */
public class Output {

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
     * @param output The output destination of an FFMpeg command. This can be a file path
     *               or a special identifier, such as a stream destination.
     * @param arguments Additional arguments to apply to this output. These represent options,
     *                  configurations, or filters associated with the output in an FFMpeg command.
     */
    public Output(String output, String... arguments) {
        this.output = output;
        Collections.addAll(this.arguments, arguments);
    }

    /**
     * Adds an argument to be applied to this Output.
     *
     * @param argument The argument to be added.
     */
    public void addArgument(String argument) {
        arguments.add(argument);
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
     * Wraps the given string in double quotes.
     *
     * @param string The string to be wrapped in double quotes.
     * @return A new string with the input wrapped in double quotes.
     */
    private static String enquote(String string) {
        return "\"" + string + "\"";
    }
}
