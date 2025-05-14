package eu.tgx03.ffmpeg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an input file to FFMpeg with additional arguments applied to it like ss.
 */
public class Input {

    /**
     * All the arguments to apply to this input.
     */
    private final List<String> arguments = new ArrayList<>();
    /**
     * The input.
     */
    private final String input;

    /**
     * Constructs an Input instance for the specified input.
     *
     * @param input The path or identifier of the input to be used with FFMpeg.
     */
    public Input(String input) {
        this.input = input;
    }

    /**
     * Constructs an Input instance for the specified input and additional arguments.
     *
     * @param input The path or identifier of the input to be used with FFMpeg.
     * @param arguments Additional arguments to apply to this input.
     */
    public Input(String input, String... arguments) {
        this.input = input;
        Collections.addAll(this.arguments, arguments);
    }

    /**
     * Adds an argument to apply to this input.
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
        builder.append("-i ").append(enquote(input));
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
