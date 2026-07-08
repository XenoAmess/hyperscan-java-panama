package com.xenoamess.hyperscan_panama.performance;

import com.xenoamess.hyperscan_panama.wrapper.Database;
import com.xenoamess.hyperscan_panama.wrapper.Expression;
import com.xenoamess.hyperscan_panama.wrapper.ExpressionFlag;
import com.xenoamess.hyperscan_panama.wrapper.Match;
import com.xenoamess.hyperscan_panama.wrapper.Scanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticDataTest {

    @Test
    void manyRandomLiteralPatterns() throws Exception {
        int count = 200;
        List<Expression> expressions = new ArrayList<>();
        List<String> expectedMatches = new ArrayList<>();
        Random random = new Random(2029);

        for (int i = 0; i < count; i++) {
            String literal = "LIT_" + String.format("%08x", random.nextInt());
            expressions.add(new Expression(Pattern.quote(literal), ExpressionFlag.SOM_LEFTMOST, i));
            if (i % 7 == 0) {
                expectedMatches.add(literal);
            }
        }

        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            StringBuilder input = new StringBuilder();
            input.append("noise_");
            for (String literal : expectedMatches) {
                input.append(literal).append("_sep_");
            }
            input.append("trailing");

            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, input.toString());
            assertThat(matches).hasSize(expectedMatches.size());
            for (Match match : matches) {
                assertThat(match.getMatchedExpression().getId() % 7).isEqualTo(0);
            }
        }
    }

    @Test
    void characterClassPatternsOnRandomAlphanumericInput() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("[0-9]{5,}", ExpressionFlag.SOM_LEFTMOST, 1),
                new Expression("[A-Z]{4,}", ExpressionFlag.SOM_LEFTMOST, 2),
                new Expression("[a-z]{6,}", ExpressionFlag.SOM_LEFTMOST, 3),
                new Expression("[0-9a-f]{32}", ExpressionFlag.SOM_LEFTMOST, 4)
        );

        try (Database database = Database.compile(expressions);
             Scanner scanner = new Scanner()) {
            Random random = new Random(42);
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                input.append((char) ('a' + random.nextInt(26)));
            }
            input.append(" 12345 ABCDE lowercase ");
            Random hexRandom = new Random(2030);
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 32; i++) {
                hex.append(String.format("%x", hexRandom.nextInt(16)));
            }
            input.append(hex);

            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, input.toString());
            assertThat(matches).isNotEmpty();
        }
    }

    @Test
    void longInputScanDoesNotCrash() throws Exception {
        Expression expression = new Expression("needle", ExpressionFlag.SOM_LEFTMOST, 1);

        try (Database database = Database.compile(expression);
             Scanner scanner = new Scanner()) {
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < 100_000; i++) {
                input.append("haystack ");
            }
            input.append("needle");
            for (int i = 0; i < 10_000; i++) {
                input.append(" tail");
            }

            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, input.toString());
            assertThat(matches).hasSize(1);
            assertThat(matches.get(0).getMatchedExpression().getId()).isEqualTo(1);
        }
    }
}
