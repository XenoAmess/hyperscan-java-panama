package com.xenoamess.hyperscan.performance;

import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import com.gliwka.hyperscan.wrapper.Match;
import com.gliwka.hyperscan.wrapper.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WrapperSmokeTest {

    private Scanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new Scanner();
    }

    @AfterEach
    void tearDown() {
        try {
            scanner.close();
        } catch (Exception ignored) {
        }
    }

    @Test
    void literalPatternMatches() throws Exception {
        Expression expression = new Expression("needle", ExpressionFlag.SOM_LEFTMOST, 1);
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "haystack needle haystack");
            assertThat(matches).hasSize(1);
            assertThat(matches.get(0)).satisfies(m -> {
                assertThat(m.getMatchedExpression().getId()).isEqualTo(1);
                assertThat(m.getStartPosition()).isEqualTo(9);
                assertThat(m.getEndPosition()).isEqualTo(14);
                assertThat(m.getMatchedString()).isEqualTo("needle");
            });
        }
    }

    @Test
    void caselessFlagMatchesDifferentCases() throws Exception {
        Expression expression = new Expression(
                "hyperscan",
                EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.SOM_LEFTMOST),
                10
        );
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "Hyperscan HYPERSCAN hyperscan");
            assertThat(matches).hasSize(3);
            assertThat(matches).allMatch(m -> m.getMatchedExpression().getId() == 10);
        }
    }

    @Test
    void multipleExpressionsReportCorrectIds() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("foo", ExpressionFlag.SOM_LEFTMOST, 1),
                new Expression("bar", ExpressionFlag.SOM_LEFTMOST, 2),
                new Expression("baz", ExpressionFlag.SOM_LEFTMOST, 3)
        );
        try (Database database = Database.compile(expressions)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "foo bar baz bar");
            assertThat(matches).hasSize(4);

            assertThat(matches.stream().filter(m -> m.getMatchedExpression().getId() == 1)).hasSize(1);
            assertThat(matches.stream().filter(m -> m.getMatchedExpression().getId() == 2)).hasSize(2);
            assertThat(matches.stream().filter(m -> m.getMatchedExpression().getId() == 3)).hasSize(1);
            assertThat(matches).extracting(Match::getMatchedString).containsExactlyInAnyOrder("foo", "bar", "baz", "bar");
        }
    }

    @Test
    void utf8PatternMatchesMultibyteCharacters() throws Exception {
        Expression expression = new Expression(
                "中文",
                EnumSet.of(ExpressionFlag.UTF8, ExpressionFlag.SOM_LEFTMOST),
                100
        );
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "this is 中文 text");
            assertThat(matches).hasSize(1);
            assertThat(matches.get(0)).satisfies(m -> {
                assertThat(m.getMatchedExpression().getId()).isEqualTo(100);
                assertThat(m.getStartPosition()).isEqualTo(8);
                assertThat(m.getEndPosition()).isEqualTo(9);
                assertThat(m.getMatchedString()).isEqualTo("中文");
            });
        }
    }

    @Test
    void noMatchReturnsEmptyList() throws Exception {
        Expression expression = new Expression("nomatch", ExpressionFlag.SOM_LEFTMOST, 1);
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "something completely different");
            assertThat(matches).isEmpty();
        }
    }

    @Test
    void emptyInputProducesNoMatchForNonEmptyPattern() throws Exception {
        Expression expression = new Expression("abc", ExpressionFlag.SOM_LEFTMOST, 1);
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "");
            assertThat(matches).isEmpty();
        }
    }

    @Test
    void allowEmptyPatternMatchesEmptyInput() throws Exception {
        Expression expression = new Expression(".*", EnumSet.of(ExpressionFlag.ALLOWEMPTY), 1);
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "");
            assertThat(matches).hasSize(1);
            assertThat(matches.get(0).getMatchedString()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"password=secret", "the password is secret", "PASSWORD=SECRET"})
    void hasMatchReturnsTrueWhenPatternExists(String input) throws Exception {
        Expression expression = new Expression(
                "password",
                EnumSet.of(ExpressionFlag.CASELESS),
                1
        );
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            assertThat(scanner.hasMatch(database, input)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello world", "no secret here", ""})
    void hasMatchReturnsFalseWhenPatternAbsent(String input) throws Exception {
        Expression expression = new Expression("password", ExpressionFlag.CASELESS, 1);
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            assertThat(scanner.hasMatch(database, input)).isFalse();
        }
    }

    @Test
    void realWorldRegexPatternsSmoke() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}", ExpressionFlag.SOM_LEFTMOST, 1),
                new Expression("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", ExpressionFlag.SOM_LEFTMOST, 2),
                new Expression("https?://[^\\s]+", ExpressionFlag.SOM_LEFTMOST, 3),
                new Expression("\\bERROR\\b", ExpressionFlag.SOM_LEFTMOST, 4),
                new Expression("[0-9]{3}-[0-9]{2}-[0-9]{4}", ExpressionFlag.SOM_LEFTMOST, 5)
        );
        try (Database database = Database.compile(expressions)) {
            scanner.allocScratch(database);
            String input = "Contact admin@example.com from 203.0.113.42. See https://example.com/page. SSN 123-45-6789. ERROR: disk full.";
            List<Match> matches = scanner.scan(database, input);
            assertThat(matches).isNotEmpty();
            assertThat(matches.stream().anyMatch(m -> m.getMatchedExpression().getId() == 1)).isTrue();
            assertThat(matches.stream().anyMatch(m -> m.getMatchedExpression().getId() == 2)).isTrue();
            assertThat(matches.stream().anyMatch(m -> m.getMatchedExpression().getId() == 3)).isTrue();
            assertThat(matches.stream().anyMatch(m -> m.getMatchedExpression().getId() == 4)).isTrue();
            assertThat(matches.stream().anyMatch(m -> m.getMatchedExpression().getId() == 5)).isTrue();
        }
    }

    @Test
    void expressionValidationWorks() {
        Expression valid = new Expression("test[0-9]+", ExpressionFlag.SOM_LEFTMOST);
        assertThat(valid.validate().isValid()).isTrue();

        Expression invalid = new Expression("test\\1", ExpressionFlag.SOM_LEFTMOST);
        assertThat(invalid.validate().isValid()).isFalse();
        assertThat(invalid.validate().getErrorMessage()).isNotEmpty();
    }

    @Test
    void databaseSerializationRoundTrip() throws Exception {
        List<Expression> expressions = Arrays.asList(
                new Expression("foo", ExpressionFlag.SOM_LEFTMOST, 1),
                new Expression("bar", ExpressionFlag.SOM_LEFTMOST, 2)
        );

        byte[] serialized;
        try (Database original = Database.compile(expressions);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            original.save(out);
            serialized = out.toByteArray();
            assertThat(serialized.length).isGreaterThan(0);
        }

        try (Database loaded = Database.load(new ByteArrayInputStream(serialized));
             Scanner loadedScanner = new Scanner()) {
            loadedScanner.allocScratch(loaded);
            List<Match> matches = loadedScanner.scan(loaded, "foo bar");
            assertThat(matches).hasSize(2);
            assertThat(matches).extracting(m -> m.getMatchedExpression().getExpression()).containsExactlyInAnyOrder("foo", "bar");
        }
    }

    @Test
    void anchoringAndDotallBehavesCorrectly() throws Exception {
        Expression expression = new Expression(
                "^start.*end$",
                EnumSet.of(ExpressionFlag.MULTILINE, ExpressionFlag.DOTALL, ExpressionFlag.SOM_LEFTMOST),
                1
        );
        try (Database database = Database.compile(expression)) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "start middle end\nstart another end");
            assertThat(matches).hasSize(2);
            assertThat(matches).allMatch(m -> m.getMatchedExpression().getId() == 1);
        }
    }
}
