package com.xenoamess.hyperscan_panama.wrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class StreamTest {

    private Scanner scanner;
    private Database streamDb;
    private Database vectorDb;

    @BeforeEach
    void setUp() {
        scanner = new Scanner();
        try {
            streamDb = Database.compile(Arrays.asList(
                    new Expression("test", 0),
                    new Expression("test1", 1)), Mode.STREAM);
            scanner.allocScratch(streamDb);
            vectorDb = Database.compile(Arrays.asList(
                    new Expression("hello", 0),
                    new Expression("world", 1)), Mode.VECTORED);
            scanner.allocScratch(vectorDb);
        } catch (CompileErrorException e) {
            fail("Database compilation failed", e);
        }
    }

    @AfterEach
    void tearDown() {
        try {
            if (streamDb != null) {
                streamDb.close();
            }
            if (vectorDb != null) {
                vectorDb.close();
            }
            if (scanner != null) {
                scanner.close();
            }
        } catch (Exception e) {
            // Ignore cleanup exceptions in tests
        }
    }

    @Test
    void streamingScanAcrossChunkBoundaryFindsMatches() {
        List<long[]> matches = new ArrayList<>();
        try (Scanner.Stream stream = scanner.openStream(streamDb)) {
            ByteMatchEventHandler handler = (expression, from, to) -> {
                matches.add(new long[]{expression.getId(), from, to});
                return true;
            };
            stream.scan("tes".getBytes(StandardCharsets.UTF_8), handler);
            stream.scan("t1".getBytes(StandardCharsets.UTF_8), handler);
        }

        assertThat(matches).hasSize(2);
        assertThat(matches).anySatisfy(m -> assertThat(m).containsExactly(0L, 0L, 4L));
        assertThat(matches).anySatisfy(m -> assertThat(m).containsExactly(1L, 0L, 5L));
    }

    @Test
    void streamCloseWithHandlerReportsPendingMatch() throws CompileErrorException {
        Database db = Database.compile(new Expression("c$", 0), Mode.STREAM);
        try {
            scanner.allocScratch(db);
            List<long[]> matches = new ArrayList<>();
            Scanner.Stream stream = scanner.openStream(db);
            stream.scan("abc".getBytes(StandardCharsets.UTF_8), (expression, from, to) -> {
                matches.add(new long[]{expression.getId(), from, to});
                return true;
            });
            assertThat(matches).isEmpty();
            stream.close((expression, from, to) -> {
                matches.add(new long[]{expression.getId(), from, to});
                return true;
            });

            assertThat(matches).hasSize(1);
            assertThat(matches.get(0)).containsExactly(0L, 0L, 3L);
        } finally {
            db.close();
        }
    }

    @Test
    void streamScanAfterCloseThrows() {
        Scanner.Stream stream = scanner.openStream(streamDb);
        stream.close();
        assertThrows(IllegalStateException.class,
                () -> stream.scan("x".getBytes(StandardCharsets.UTF_8), (expression, from, to) -> true));
    }

    @Test
    void vectoredScanFindsMatchesAcrossSegments() {
        List<long[]> matches = new ArrayList<>();
        scanner.scanVector(vectorDb,
                new byte[][]{"hello ".getBytes(StandardCharsets.UTF_8), "world!".getBytes(StandardCharsets.UTF_8)},
                (expression, from, to) -> {
                    matches.add(new long[]{expression.getId(), from, to});
                    return true;
                });

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0)).containsExactly(0L, 0L, 5L);
        assertThat(matches.get(1)).containsExactly(1L, 0L, 11L);
    }

    @Test
    void vectoredScanSupportsDirectAndHeapBuffers() {
        ByteBuffer direct = ByteBuffer.allocateDirect(6);
        direct.put("hello ".getBytes(StandardCharsets.UTF_8));
        ((java.nio.Buffer) direct).flip();
        ByteBuffer heap = ByteBuffer.wrap("world!".getBytes(StandardCharsets.UTF_8));

        List<long[]> matches = new ArrayList<>();
        scanner.scanVector(vectorDb, new ByteBuffer[]{direct, heap}, (expression, from, to) -> {
            matches.add(new long[]{expression.getId(), from, to});
            return true;
        });

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0)).containsExactly(0L, 0L, 5L);
        assertThat(matches.get(1)).containsExactly(1L, 0L, 11L);
        assertThat(direct.position()).isEqualTo(0);
        assertThat(direct.limit()).isEqualTo(6);
    }

    @Test
    void openStreamRejectsNonStreamingDatabase() throws CompileErrorException {
        Database blockDb = Database.compile(new Expression("test", 0));
        try {
            assertThrows(IllegalArgumentException.class, () -> scanner.openStream(blockDb));
        } finally {
            blockDb.close();
        }
    }

    @Test
    void scanVectorRejectsNonVectoredDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> scanner.scanVector(streamDb, new byte[][]{"x".getBytes(StandardCharsets.UTF_8)},
                        (expression, from, to) -> true));
    }

    @Test
    void openStreamKeepsDatabaseAndScannerAlive() {
        Scanner.Stream stream = scanner.openStream(streamDb);

        assertThrows(IllegalStateException.class, streamDb::close);
        assertThrows(IllegalStateException.class, scanner::close);

        stream.close();
        assertDoesNotThrow(streamDb::close);
        assertDoesNotThrow(scanner::close);
    }

    @Test
    void openStreamAfterScannerCloseThrows() throws IOException {
        scanner.close();

        assertThrows(IllegalStateException.class, () -> scanner.openStream(streamDb));
    }

    @Test
    void vectoredCallbackExceptionIsRethrown() {
        RuntimeException failure = new RuntimeException("vectored callback failed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> scanner.scanVector(
                vectorDb,
                new byte[][]{"hello".getBytes(StandardCharsets.UTF_8)},
                (expression, from, to) -> {
                    throw failure;
                }));

        assertSame(failure, thrown);
    }

    @Test
    void streamCallbackExceptionIsRethrown() {
        RuntimeException failure = new RuntimeException("stream callback failed");
        try (Scanner.Stream stream = scanner.openStream(streamDb)) {
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> stream.scan(
                    "test".getBytes(StandardCharsets.UTF_8),
                    (expression, from, to) -> {
                        throw failure;
                    }));

            assertSame(failure, thrown);
        }
    }

    @Test
    void streamCannotCloseItselfFromCallback() {
        Scanner.Stream stream = scanner.openStream(streamDb);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> stream.scan(
                "test".getBytes(StandardCharsets.UTF_8),
                (expression, from, to) -> {
                    stream.close();
                    return true;
                }));

        assertThat(thrown).hasMessageContaining("own callback");
        assertDoesNotThrow(() -> stream.close());
    }

    @Test
    void streamCloseCallbackExceptionStillClosesStream() throws CompileErrorException, IOException {
        RuntimeException failure = new RuntimeException("stream close callback failed");
        try (Database db = Database.compile(new Expression("c$", 0), Mode.STREAM);
             Scanner localScanner = new Scanner()) {
            localScanner.allocScratch(db);
            Scanner.Stream stream = localScanner.openStream(db);
            stream.scan("abc".getBytes(StandardCharsets.UTF_8), (expression, from, to) -> true);

            RuntimeException thrown = assertThrows(RuntimeException.class, () -> stream.close(
                    (expression, from, to) -> {
                        throw failure;
                    }));

            assertSame(failure, thrown);
            assertDoesNotThrow(() -> stream.close());
            assertDoesNotThrow(db::close);
            assertDoesNotThrow(localScanner::close);
        }
    }
}
