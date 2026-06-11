package de.skyengine.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StringUtils {

    /**
     * Returns a repeated string
     * @param character that you want to repeat
     * @param count number how often the string should repeat
     * @return the repeated string
     */
    public static String repeat(char character, int count) {
        if (count <= 0) throw new IllegalStateException("The count needs to be bigger than zero.");

        char[] data = new char[count];
        Arrays.fill(data, character);
        return new String(data);
    }

    /**
     * Fills the left side of the string with characters.
     */
    public static String padLeft(String text, int size, char padChar) {
        if (text == null) throw new IllegalStateException("The text cannot be empty or null.");

        int pads = size - text.length();
        if (pads <= 0) {
            return text;
        }
        return repeat(padChar, pads).concat(text);
    }

    /**
     * Fills the right side of the string with characters.
     */
    public static String padRight(String text, int size, char padChar) {
        if (text == null) throw new IllegalStateException("The text cannot be null.");

        int pads = size - text.length();
        if (pads <= 0) {
            return text;
        }
        return text.concat(repeat(padChar, pads));
    }


    public static String padBoth(String text, int size, char padChar) {
        if (text == null) throw new IllegalStateException("The text cannot be null.");
        if (size <= 0) throw new IllegalStateException("The size must be bigger than zero.");

        int length = text.length();
        int pads = size - length;
        if (pads <= 0) {
            return text;
        }

        text = padLeft(text, length + pads / 2, padChar);
        text = padRight(text, size, padChar);
        return text;
    }

    public static String[] removeEmptyStrings(String[] array) {
        List<String> list = new ArrayList<>();

        for (String s : array) {
            if (!s.isEmpty()) {
                list.add(s);
            }
        }

        String[] result = new String[list.size()];
        return list.toArray(result);
    }

    public static String removeLastChar(String s) {
        return !s.isEmpty() ? s.substring(0, s.length() - 1) : "";
    }
}
