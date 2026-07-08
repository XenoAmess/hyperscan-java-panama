package com.xenoamess.hyperscan_panama.wrapper;

/**
 * Represents a match found during the scan
 */
public class Match {
    private long startPosition;
    private long endPosition;
    private String input;
    private String matchedString;
    private Expression matchedExpression;

    /**
     * Creates a new Match object with the specified positions, matched string, and expression.
     *
     * @param start The starting character position (inclusive) of the match in the input string
     * @param end The ending character position (inclusive) of the match in the input string
     * @param match The actual text that was matched, or empty string if SOM_LEFTMOST flag was not used
     * @param expression The expression that matched the input
     */
    public Match(long start, long end, String match, Expression expression) {
        startPosition = start;
        endPosition = end;
        input = null;
        matchedString = match;
        matchedExpression = expression;
    }

    /**
     * Creates a new Match object that lazily computes the matched string from the original input.
     * This avoids the cost of {@link String#substring(int, int)} for matches that are never inspected.
     *
     * @param input The original input string that was scanned
     * @param start The starting character position (inclusive) of the match in the input string
     * @param end The ending character position (inclusive) of the match in the input string
     * @param expression The expression that matched the input
     */
    Match(String input, long start, long end, Expression expression) {
        startPosition = start;
        endPosition = end;
        this.input = input;
        matchedString = null;
        matchedExpression = expression;
    }

    /**
     * Get the exact matched string
     * @return matched string if SOM flag is set, otherwise empty string
     */
    public String getMatchedString() {
        if (matchedString == null && input != null) {
            matchedString = input.substring((int) startPosition, (int) endPosition + 1);
        }
        return matchedString;
    }

    /** Get the start position of the match
     * @return if the SOM flag is set the position of the match, otherwise zero.
     */
    public long getStartPosition() {
        return startPosition;
    }

    /**
     * Get the end position of the match (inclusive)
     * @return end position of match regardless of flags
     */
    public long getEndPosition() {
        return endPosition;
    }

    /**
     * Get the Expression object used to find the match
     * @return Expression instance
     */
    public Expression getMatchedExpression() {
        return matchedExpression;
    }
}
